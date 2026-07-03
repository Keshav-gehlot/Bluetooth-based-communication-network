package com.meshchat.transports

import com.meshchat.core.*
import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates both [BluetoothNearbyTransport] and [WifiNearbyTransport].
 *
 * - In [TransportMode.BLUETOOTH]: only BT transport is active.
 * - In [TransportMode.WIFI]: only WiFi transport is active.
 * - In [TransportMode.BOTH]: both run simultaneously; peer sets are merged.
 *
 * Switching:
 * - New transport is STARTED before old is STOPPED to avoid connectivity gaps.
 * - Mode is persisted to DataStore via [IdentityRepository.saveTransportMode].
 *
 * Send semantics in BOTH mode:
 * - Tries BT first; falls back to WiFi on failure.
 * - Broadcast runs on both in parallel.
 */
@Singleton
class DualTransportManager @Inject constructor(
    val btTransport: BluetoothNearbyTransport,
    val wifiTransport: WifiNearbyTransport,
    private val identityRepository: IdentityRepository,
) : TransportAdapter {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _modeFlow = MutableStateFlow(TransportMode.BLUETOOTH)
    val modeFlow: StateFlow<TransportMode> = _modeFlow.asStateFlow()

    // ── Merged peer set ────────────────────────────────────────────────────────
    override val peersFlow: StateFlow<Set<String>> = combine(
        btTransport.peersFlow,
        wifiTransport.peersFlow,
        _modeFlow,
    ) { btPeers, wifiPeers, mode ->
        when (mode) {
            TransportMode.BLUETOOTH -> btPeers
            TransportMode.WIFI      -> wifiPeers
            TransportMode.BOTH      -> btPeers + wifiPeers
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), emptySet())

    // ── Merged incoming packets ────────────────────────────────────────────────
    override val incomingPackets: Flow<Packet> = merge(
        btTransport.incomingPackets
            .filter { _modeFlow.value != TransportMode.WIFI },
        wifiTransport.incomingPackets
            .filter { _modeFlow.value != TransportMode.BLUETOOTH },
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Start the manager. Reads the persisted transport mode from DataStore,
     * then starts the appropriate transport(s).
     */
    override fun start(localNodeId: String) {
        // localNodeId is ignored here — we resolve from IdentityRepository
        scope.launch {
            val mode = identityRepository.getTransportMode().first()
            _modeFlow.value = mode
            val identity = identityRepository.observeIdentity().first()
            when (mode) {
                TransportMode.BLUETOOTH -> btTransport.start(identity.btNodeId)
                TransportMode.WIFI      -> wifiTransport.start(identity.wifiNodeId)
                TransportMode.BOTH      -> {
                    btTransport.start(identity.btNodeId)
                    wifiTransport.start(identity.wifiNodeId)
                }
            }
            Timber.d("DualTransportManager started in mode=$mode")
        }
    }

    override fun stop() {
        btTransport.stop()
        wifiTransport.stop()
        Timber.d("DualTransportManager stopped")
    }

    // ── Runtime mode switch ────────────────────────────────────────────────────

    /**
     * Switch to [newMode] at runtime.
     * Starts new transport before stopping old to preserve connectivity.
     */
    suspend fun switchMode(newMode: TransportMode) {
        val current = _modeFlow.value
        if (current == newMode) return

        val identity = identityRepository.observeIdentity().first()

        // 1. Start new transport(s) first
        when (newMode) {
            TransportMode.BLUETOOTH -> btTransport.start(identity.btNodeId)
            TransportMode.WIFI      -> wifiTransport.start(identity.wifiNodeId)
            TransportMode.BOTH      -> {
                btTransport.start(identity.btNodeId)
                wifiTransport.start(identity.wifiNodeId)
            }
        }

        // 2. Stop what we no longer need
        when {
            newMode == TransportMode.BLUETOOTH && current == TransportMode.WIFI -> wifiTransport.stop()
            newMode == TransportMode.WIFI && current == TransportMode.BLUETOOTH -> btTransport.stop()
            newMode == TransportMode.BLUETOOTH && current == TransportMode.BOTH -> wifiTransport.stop()
            newMode == TransportMode.WIFI && current == TransportMode.BOTH      -> btTransport.stop()
            // BOTH: nothing to stop — already started both above
        }

        _modeFlow.value = newMode
        identityRepository.saveTransportMode(newMode)
        Timber.d("Transport mode switched $current → $newMode")
    }

    // ── Send ───────────────────────────────────────────────────────────────────

    override suspend fun sendPacket(packet: Packet) {
        val bytes = kotlinx.serialization.json.Json.encodeToString(
            Packet.serializer(), packet
        ).encodeToByteArray()

        val target = packet.targetId
        if (target != null) {
            send(target, bytes)
        } else {
            broadcast(bytes)
        }
    }

    suspend fun send(peerId: String, data: ByteArray) {
        when (_modeFlow.value) {
            TransportMode.BLUETOOTH -> btTransport.send(peerId, data)
            TransportMode.WIFI      -> wifiTransport.send(peerId, data)
            TransportMode.BOTH      -> {
                // Try BT first; fall back to WiFi on any error
                runCatching { btTransport.send(peerId, data) }
                    .onFailure {
                        Timber.w("BOTH: BT send failed, falling back to WiFi for ...${peerId.takeLast(4)}")
                        runCatching { wifiTransport.send(peerId, data) }
                            .onFailure { Timber.e(it, "BOTH: WiFi fallback also failed") }
                    }
            }
        }
    }

    suspend fun broadcast(data: ByteArray) {
        when (_modeFlow.value) {
            TransportMode.BLUETOOTH -> btTransport.broadcast(data)
            TransportMode.WIFI      -> wifiTransport.broadcast(data)
            TransportMode.BOTH      -> {
                // Both in parallel
                coroutineScope {
                    launch { btTransport.broadcast(data) }
                    launch { wifiTransport.broadcast(data) }
                }
            }
        }
    }
}
