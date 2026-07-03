package com.meshchat.transports

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.meshchat.core.*
import com.meshchat.domain.repository.IdentityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @deprecated Replaced by [BluetoothNearbyTransport] and [WifiNearbyTransport]
 * which are orchestrated by [DualTransportManager].
 * This class is kept during migration and will be removed in a future version.
 */
@Deprecated("Use DualTransportManager instead")
@Singleton
class NearbyConnectionsTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityRepository: IdentityRepository,
    private val presenceManager: PresenceManager
) : TransportAdapter {


    companion object {
        const val SERVICE_ID = "com.meshchat.nearby"
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    
    private val _incomingChannel = Channel<Packet>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
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

    private val json = Json { ignoreUnknownKeys = true }

    override fun start(localNodeId: String) {
        this.localNodeId = localNodeId
        startAdvertisingInternal()
        startDiscoveryInternal()
    }

    private fun startAdvertisingInternal() {
        val nodeId = localNodeId ?: return
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            nodeId,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            isAdvertising = true
            Timber.d("Successfully started advertising for nodeId: $nodeId")
        }.addOnFailureListener { e ->
            if (e is ApiException && e.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                isAdvertising = true
                Timber.d("Already advertising, ignoring.")
            } else {
                Timber.e(e, "Failed to start advertising. Retrying in 3 seconds...")
                retryAdvertising()
            }
        }
    }

    private fun retryAdvertising() {
        retryAdvertisingJob?.cancel()
        retryAdvertisingJob = scope.launch {
            delay(3000)
            startAdvertisingInternal()
        }
    }

    private fun startDiscoveryInternal() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            isDiscovering = true
            Timber.d("Successfully started discovery")
        }.addOnFailureListener { e ->
            if (e is ApiException && e.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                isDiscovering = true
                Timber.d("Already discovering, ignoring.")
            } else {
                Timber.e(e, "Failed to start discovery. Retrying in 3 seconds...")
                retryDiscovery()
            }
        }
    }

    private fun retryDiscovery() {
        retryDiscoveryJob?.cancel()
        retryDiscoveryJob = scope.launch {
            delay(3000)
            startDiscoveryInternal()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val localId = localNodeId ?: return
            Timber.d("Endpoint found: $endpointId (${info.endpointName}). Requesting connection...")
            
            // Map immediately to keep track
            endpointToNodeMap[endpointId] = info.endpointName
            nodeToEndpointMap[info.endpointName] = endpointId

            if (localId < info.endpointName) {
                Timber.d("Local ID $localId < Remote ID ${info.endpointName}. Requesting connection...")
                connectionsClient.requestConnection(
                    localId,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener { e ->
                    Timber.e(e, "Failed to request connection to $endpointId")
                }
            } else {
                Timber.d("Local ID $localId >= Remote ID ${info.endpointName}. Waiting for remote side to initiate connection.")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.d("Endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.d("Connection initiated with $endpointId (${connectionInfo.endpointName})")
            
            endpointToNodeMap[endpointId] = connectionInfo.endpointName
            nodeToEndpointMap[connectionInfo.endpointName] = endpointId

            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to accept connection from $endpointId")
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val nodeId = endpointToNodeMap[endpointId]
            if (resolution.status.isSuccess) {
                Timber.d("Connection established with $endpointId ($nodeId)")
                if (nodeId != null) {
                    reconnectAttempts.remove(endpointId)
                    _peersFlow.value = endpointToNodeMap.values.toSet()
                    scope.launch {
                        sendPresencePacket(nodeId)
                    }
                }
            } else {
                Timber.w("Connection request failed to $endpointId with status: ${resolution.status}")
                handleDisconnect(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val nodeId = endpointToNodeMap[endpointId]
            Timber.d("Disconnected from $endpointId ($nodeId)")

            val attempts = reconnectAttempts[endpointId] ?: 0
            if (attempts < 1 && nodeId != null) {
                reconnectAttempts[endpointId] = attempts + 1
                Timber.d("Attempting to reconnect once to $endpointId ($nodeId)")

                val localId = localNodeId
                if (localId != null) {
                    connectionsClient.requestConnection(
                        localId,
                        endpointId,
                        this
                    ).addOnFailureListener { e ->
                        Timber.e(e, "Failed reconnect request to $endpointId. Removing peer.")
                        handleDisconnect(endpointId)
                    }
                } else {
                    handleDisconnect(endpointId)
                }
            } else {
                handleDisconnect(endpointId)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch {
                try {
                    val jsonString = String(bytes, Charsets.UTF_8)
                    val packet = json.decodeFromString<Packet>(jsonString)

                    if (packet.type == PacketType.SYSTEM) {
                        presenceManager.handlePresencePacket(packet)
                    }

                    val result = _incomingChannel.trySend(packet)
                    if (result.isFailure) {
                        Timber.w("Packet dropped — channel full, endpointId=$endpointId")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse incoming packet from endpoint $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op
        }
    }

    private fun handleDisconnect(endpointId: String) {
        val nodeId = endpointToNodeMap.remove(endpointId)
        if (nodeId != null) {
            nodeToEndpointMap.remove(nodeId)
            _peersFlow.value = endpointToNodeMap.values.toSet()
            presenceManager.setPeerOffline(nodeId)
        }
        reconnectAttempts.remove(endpointId)
    }

    private fun markPeerStale(endpointId: String) {
        val nodeId = endpointToNodeMap.remove(endpointId)
        if (nodeId != null) {
            nodeToEndpointMap.remove(nodeId)
            _peersFlow.value = endpointToNodeMap.values.toSet()
        }
    }

    private suspend fun sendPresencePacket(peerNodeId: String) {
        val localId = localNodeId ?: return
        try {
            val ownIdentity = identityRepository.observeIdentity().first()
            val presenceData = PresencePayload(
                username = ownIdentity.username,
                btNodeId = ownIdentity.btNodeId,
                wifiNodeId = ownIdentity.wifiNodeId,
                avatarSeed = ownIdentity.avatarSeed,
                activeTransport = TransportMode.BLUETOOTH,
                timestamp = System.currentTimeMillis(),
                usernameClaimed = ownIdentity.usernameClaimed
            )
            val presenceJson = json.encodeToString(presenceData)
            val payloadBytes = presenceJson.toByteArray(Charsets.UTF_8)

            val packet = Packet(
                id = UUID.randomUUID().toString(),
                type = PacketType.SYSTEM,
                senderId = localId,
                targetId = peerNodeId,
                payload = payloadBytes,
                timestamp = System.currentTimeMillis(),
                hopCount = 0
            )

            sendPacket(packet)
            Timber.d("Sent presence packet to $peerNodeId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send presence packet to $peerNodeId")
        }
    }

    suspend fun send(peerId: String, data: ByteArray) {
        val endpointId = nodeToEndpointMap[peerId]
        if (endpointId != null) {
            val payload = Payload.fromBytes(data)
            try {
                withTimeout(3_000L) {
                    connectionsClient.sendPayload(endpointId, payload).await()
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w("unicast send timeout: $peerId ($endpointId)")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "unicast send failed: $peerId ($endpointId)")
            }
        } else {
            Timber.w("Cannot send payload: no endpoint mapped for peer $peerId")
        }
    }

    suspend fun broadcast(data: ByteArray) {
        val endpoints = nodeToEndpointMap.values.toList()
        if (endpoints.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                endpoints.map { endpointId ->
                    async {
                        try {
                            withTimeout(3_000L) {
                                connectionsClient.sendPayload(endpointId, Payload.fromBytes(data)).await()
                            }
                        } catch (e: TimeoutCancellationException) {
                            Timber.w("broadcast timeout: $endpointId — marking stale")
                            markPeerStale(endpointId)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Timber.w(e, "broadcast send error: $endpointId")
                            markPeerStale(endpointId)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    override suspend fun sendPacket(packet: Packet) {
        try {
            val jsonString = json.encodeToString(packet)
            val bytes = jsonString.toByteArray(Charsets.UTF_8)
            val targetId = packet.targetId
            if (targetId != null) {
                send(targetId, bytes)
            } else {
                broadcast(bytes)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to serialize and send packet: ${packet.id}")
        }
    }

    override fun stop() {
        _incomingChannel.close()
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
    }
}
