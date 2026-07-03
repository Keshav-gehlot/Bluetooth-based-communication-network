package com.meshchat.transports

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.meshchat.core.*
import com.meshchat.domain.repository.IdentityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi-optimised transport using Nearby Connections P2P_CLUSTER strategy.
 *
 * - Higher bandwidth, longer range, uses WiFi Direct.
 * - Only connects to nodes whose ID starts with "WIFI-".
 * - Chunk size: 65 536 bytes (64KB — WiFi Direct optimal).
 */
@Singleton
class WifiNearbyTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityRepository: IdentityRepository,
    private val presenceManager: PresenceManager,
) : TransportAdapter {

    companion object {
        const val SERVICE_ID = "com.meshchat.wifi"
        const val NODE_PREFIX = "WIFI-"
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val _incomingChannel = Channel<Packet>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingPackets: Flow<Packet> = _incomingChannel.receiveAsFlow()

    private val _peersFlow = MutableStateFlow<Set<String>>(emptySet())
    override val peersFlow: StateFlow<Set<String>> = _peersFlow.asStateFlow()

    private var localNodeId: String? = null
    private var isAdvertising = false
    private var isDiscovering = false

    private val nodeToEndpointMap = ConcurrentHashMap<String, String>()
    private val endpointToNodeMap = ConcurrentHashMap<String, String>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var retryAdvertisingJob: Job? = null
    private var retryDiscoveryJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun start(localNodeId: String) {
        require(localNodeId.startsWith(NODE_PREFIX)) {
            "WifiNearbyTransport requires a WIFI-prefixed nodeId, got: $localNodeId"
        }
        this.localNodeId = localNodeId
        startAdvertising()
        startDiscovery()
    }

    override fun stop() {
        retryAdvertisingJob?.cancel()
        retryDiscoveryJob?.cancel()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        isAdvertising = false
        isDiscovering = false
        nodeToEndpointMap.clear()
        endpointToNodeMap.clear()
        reconnectAttempts.clear()
        _peersFlow.value = emptySet()
        Timber.d("WifiTransport stopped")
    }

    // ── Advertising ────────────────────────────────────────────────────────────

    private fun startAdvertising() {
        val nodeId = localNodeId ?: return
        connectionsClient.startAdvertising(
            nodeId,
            SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnSuccessListener {
            isAdvertising = true
            Timber.d("WiFi advertising started — ...${nodeId.takeLast(4)}")
        }.addOnFailureListener { e ->
            if (e is ApiException && e.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                isAdvertising = true
            } else {
                Timber.e(e, "WiFi advertising failed — retrying")
                retryAdvertisingJob?.cancel()
                retryAdvertisingJob = scope.launch { delay(3_000); startAdvertising() }
            }
        }
    }

    // ── Discovery ──────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnSuccessListener {
            isDiscovering = true
            Timber.d("WiFi discovery started")
        }.addOnFailureListener { e ->
            if (e is ApiException && e.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                isDiscovering = true
            } else {
                Timber.e(e, "WiFi discovery failed — retrying")
                retryDiscoveryJob?.cancel()
                retryDiscoveryJob = scope.launch { delay(3_000); startDiscovery() }
            }
        }
    }

    // ── Callbacks ──────────────────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!info.endpointName.startsWith(NODE_PREFIX)) return
            val localId = localNodeId ?: return
            endpointToNodeMap[endpointId] = info.endpointName
            nodeToEndpointMap[info.endpointName] = endpointId
            if (localId < info.endpointName) {
                connectionsClient.requestConnection(localId, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e -> Timber.e(e, "WiFi connection request failed") }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.d("WiFi endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!info.endpointName.startsWith(NODE_PREFIX)) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
            endpointToNodeMap[endpointId] = info.endpointName
            nodeToEndpointMap[info.endpointName] = endpointId
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val nodeId = endpointToNodeMap[endpointId] ?: return
            if (resolution.status.isSuccess) {
                reconnectAttempts.remove(endpointId)
                _peersFlow.value = endpointToNodeMap.values.toSet()
                scope.launch { sendPresencePacket(nodeId) }
                Timber.d("WiFi connected — ...${nodeId.takeLast(4)}")
            } else {
                handleDisconnect(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val nodeId = endpointToNodeMap[endpointId]
            val attempts = reconnectAttempts[endpointId] ?: 0
            if (attempts < 1 && nodeId != null) {
                reconnectAttempts[endpointId] = attempts + 1
                localNodeId?.let { localId ->
                    connectionsClient.requestConnection(localId, endpointId, this)
                        .addOnFailureListener { handleDisconnect(endpointId) }
                } ?: handleDisconnect(endpointId)
            } else {
                handleDisconnect(endpointId)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch {
                runCatching {
                    val packet = json.decodeFromString<Packet>(String(bytes, Charsets.UTF_8))
                    if (packet.type == PacketType.SYSTEM || packet.type == PacketType.PRESENCE) {
                        presenceManager.handlePresencePacket(packet)
                    }
                    _incomingChannel.trySend(packet).also { result ->
                        if (result.isFailure) Timber.w("WiFi packet dropped — channel full")
                    }
                }.onFailure { Timber.e(it, "WiFi packet parse error") }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    // ── Send ───────────────────────────────────────────────────────────────────

    override suspend fun sendPacket(packet: Packet) {
        val bytes = json.encodeToString(packet).encodeToByteArray()
        val target = packet.targetId
        if (target != null) send(target, bytes) else broadcast(bytes)
    }

    suspend fun send(peerId: String, data: ByteArray) {
        val endpointId = nodeToEndpointMap[peerId] ?: run {
            Timber.w("WiFi send: no endpoint for $peerId")
            return
        }
        runCatching {
            withTimeout(3_000L) {
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(data)).await()
            }
        }.onFailure { Timber.w(it, "WiFi unicast failed to ...${peerId.takeLast(4)}") }
    }

    suspend fun broadcast(data: ByteArray) {
        val endpoints = nodeToEndpointMap.entries.toList()
        coroutineScope {
            endpoints.map { (nodeId, endpointId) ->
                async {
                    runCatching {
                        withTimeout(3_000L) {
                            connectionsClient.sendPayload(endpointId, Payload.fromBytes(data)).await()
                        }
                    }.onFailure {
                        Timber.w(it, "WiFi broadcast failed — removing ...${nodeId.takeLast(4)}")
                        markPeerStale(endpointId)
                    }
                }
            }.awaitAll()
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun handleDisconnect(endpointId: String) {
        val nodeId = endpointToNodeMap.remove(endpointId) ?: return
        nodeToEndpointMap.remove(nodeId)
        _peersFlow.value = endpointToNodeMap.values.toSet()
        presenceManager.setPeerOffline(nodeId)
        reconnectAttempts.remove(endpointId)
    }

    private fun markPeerStale(endpointId: String) {
        val nodeId = endpointToNodeMap.remove(endpointId) ?: return
        nodeToEndpointMap.remove(nodeId)
        _peersFlow.value = endpointToNodeMap.values.toSet()
    }

    private suspend fun sendPresencePacket(peerNodeId: String) {
        val localId = localNodeId ?: return
        runCatching {
            val identity = identityRepository.observeIdentity().first()
            val presence = PresencePayload(
                username        = identity.username,
                btNodeId        = identity.btNodeId,
                wifiNodeId      = identity.wifiNodeId,
                avatarSeed      = identity.avatarSeed,
                activeTransport = TransportMode.WIFI,
                timestamp       = System.currentTimeMillis(),
                usernameClaimed = identity.usernameClaimed,
            )
            sendPacket(
                Packet(
                    id        = UUID.randomUUID().toString(),
                    type      = PacketType.PRESENCE,
                    senderId  = localId,
                    targetId  = peerNodeId,
                    payload   = Json.encodeToString(presence).encodeToByteArray(),
                    timestamp = System.currentTimeMillis(),
                )
            )
        }.onFailure { Timber.e(it, "WiFi presence send failed") }
    }
}
