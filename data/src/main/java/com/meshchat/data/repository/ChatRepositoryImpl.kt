package com.meshchat.data.repository

import com.meshchat.core.MeshNode
import com.meshchat.core.NodeConfig
import com.meshchat.core.Packet
import com.meshchat.core.PacketType
import com.meshchat.core.PresenceManager
import com.meshchat.core.TransportAdapter
import com.meshchat.core.TransportMode
import com.meshchat.core.UsernameClaimProtocol
import com.meshchat.core.VoiceBridge
import com.meshchat.data.database.MessageDao
import com.meshchat.data.database.MessageEntity
import com.meshchat.data.media.MediaTransferEngine
import com.meshchat.domain.model.ChatPayload
import com.meshchat.domain.model.Conversation
import com.meshchat.domain.model.EncryptedPacket
import com.meshchat.domain.model.Message
import com.meshchat.domain.model.MessageStatus
import com.meshchat.domain.model.NodeIdentity
import com.meshchat.domain.repository.ChatRepository
import com.meshchat.domain.repository.CryptoRepository
import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val transport: TransportAdapter,
    private val identityRepository: IdentityRepository,
    private val presenceManager: PresenceManager,
    private val cryptoRepository: CryptoRepository,
    private val claimProtocol: UsernameClaimProtocol,
    private val mediaTransferEngine: MediaTransferEngine,
    private val voiceBridge: VoiceBridge,
) : ChatRepository {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var meshNode: MeshNode? = null
    private val isMeshStarted = MutableStateFlow(false)
    private val writeChannel = Channel<MessageEntity>(capacity = 256)
    private val onlinePeerIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch(Dispatchers.IO) {
            writeChannel.batch(maxSize = 20, timeoutMs = 100).collect { batch ->
                runCatching { messageDao.insertAll(batch) }
                    .onFailure { Timber.e(it, "Batch insert failed") }
            }
        }

        // Synchronize onlinePeerIds with presenceManager updates
        scope.launch {
            presenceManager.peers.collect { peerMap ->
                val onlineIds = peerMap.values.filter { it.isOnline }
                    .flatMap { listOf(it.btNodeId, it.wifiNodeId) }
                    .toSet()
                onlinePeerIds.retainAll(onlineIds)
            }
        }

        scope.launch {
            combine(
                identityRepository.observeIdentity(),
                identityRepository.getTransportMode()
            ) { identity, mode ->
                identity to mode
            }.collect { (identity, mode) ->
                if (identity.username.isNotEmpty() && !isMeshStarted.value) {
                    isMeshStarted.value = true
                    
                    val activeNodeId = identityRepository.getActiveNodeId(mode)
                    Timber.d("Starting MeshNode with username: ${identity.username}, activeNodeId: $activeNodeId")
                    
                    // Start physical transport
                    transport.start(activeNodeId)

                    val savedMaxHops = identityRepository.getMaxHops().first()
                    val savedIdsEnabled = identityRepository.getIdsEnabled().first()

                    // Instantiate the MeshNode router
                    val node = MeshNode(
                        nodeId = activeNodeId,
                        config = NodeConfig(
                            maxHops = savedMaxHops,
                            idsEnabled = savedIdsEnabled
                        ),
                        transport = transport
                    )
                    meshNode = node
                    claimProtocol.setMeshNode(node)
                    voiceBridge.setMeshNode(node)
                    node.start(scope)

                    // Listen to incoming packets routed through the MeshNode
                    node.receivedPackets.collect { packet ->
                        handleIncomingPacket(packet, identity)
                    }
                }
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: Packet, ownIdentity: NodeIdentity) {
        val peer = if (packet.senderId.startsWith("WIFI-")) {
            presenceManager.getPeerByWifiId(packet.senderId)
        } else {
            presenceManager.getPeerByBtId(packet.senderId)
        }
        val senderUsername = peer?.username ?: packet.senderId.takeLast(6)
        val senderDisplayName = peer?.username ?: "Peer ...${packet.senderId.takeLast(4)}"

        when (packet.type) {
            PacketType.CHAT -> {
                // Addressable by either our BT node ID, our WiFi node ID, or our username
                if (packet.targetId == ownIdentity.btNodeId || 
                    packet.targetId == ownIdentity.wifiNodeId || 
                    packet.targetId == ownIdentity.username) {
                    val decryptedResult = cryptoRepository.decryptMessage(EncryptedPacket(packet.payload))
                    if (decryptedResult.isSuccess) {
                        val text = decryptedResult.getOrThrow().text
                        val convoId = generateConversationId(ownIdentity.username, senderUsername)
                        val message = Message(
                            id = packet.id,
                            text = text,
                            senderId = senderUsername,
                            senderName = senderDisplayName,
                            timestamp = packet.timestamp,
                            isOutgoing = false,
                            status = MessageStatus.DELIVERED,
                            hopCount = packet.hopCount,
                            conversationId = convoId
                        )
                        writeChannel.send(MessageEntity.fromDomain(message))
                    } else {
                        Timber.e("Failed to decrypt direct chat packet from ${packet.senderId}")
                    }
                }
            }
            PacketType.BROADCAST -> {
                val decryptedResult = cryptoRepository.decryptMessage(EncryptedPacket(packet.payload))
                if (decryptedResult.isSuccess) {
                    val payload = decryptedResult.getOrThrow()
                    val text = payload.text
                    val message = Message(
                        id = packet.id,
                        text = text,
                        senderId = senderUsername,
                        senderName = senderDisplayName,
                        timestamp = packet.timestamp,
                        isOutgoing = false,
                        status = MessageStatus.DELIVERED,
                        hopCount = packet.hopCount,
                        conversationId = "broadcast_${payload.roomId}"
                    )
                    writeChannel.send(MessageEntity.fromDomain(message))
                } else {
                    Timber.e("Failed to decrypt broadcast packet from ${packet.senderId}")
                }
            }
            PacketType.USERNAME_CLAIM -> {
                claimProtocol.onClaimReceived(packet)
            }
            PacketType.USERNAME_REGISTRY -> {
                claimProtocol.onRegistryReceived(packet)
            }
            PacketType.USERNAME_RELEASE -> {
                claimProtocol.onReleaseReceived(packet)
            }
            PacketType.PRESENCE -> {
                // Gossip our registry to them if they are newly discovered online
                val isNewOnline = onlinePeerIds.add(packet.senderId)
                if (isNewOnline) {
                    claimProtocol.onPeerConnected(packet.senderId, ownIdentity.btNodeId)
                }
            }
            PacketType.VOICE_START -> {
                val payload = kotlinx.serialization.json.Json.decodeFromString<com.meshchat.core.VoiceStartPayload>(String(packet.payload))
                voiceBridge.onVoiceStart(payload)
            }
            PacketType.VOICE_FRAME -> {
                val payload = kotlinx.serialization.json.Json.decodeFromString<com.meshchat.core.VoiceFramePayload>(String(packet.payload))
                voiceBridge.onVoiceFrame(payload)
            }
            PacketType.VOICE_END -> {
                val payload = kotlinx.serialization.json.Json.decodeFromString<com.meshchat.core.VoiceEndPayload>(String(packet.payload))
                val convoId = if (packet.targetId == "BROADCAST") "group_broadcast" else generateConversationId(ownIdentity.username, senderUsername)
                voiceBridge.onVoiceEnd(payload, convoId)
            }
            else -> {}
        }
    }

    override suspend fun sendMessage(payload: EncryptedPacket): Result<Unit> {
        return try {
            val ownIdentity = identityRepository.observeIdentity().first()
            val ownName = ownIdentity.username.ifEmpty { "Me" }
            
            val decryptedResult = cryptoRepository.decryptMessage(payload)
            val payloadText = decryptedResult.getOrNull()?.text ?: "Message (Encrypted)"
            val chatPayload = ChatPayload.decode(payloadText)
            val dst = chatPayload?.dst ?: "" // peer's username
            val text = chatPayload?.text ?: payloadText

            if (dst.isBlank()) {
                return Result.failure(IllegalArgumentException("Invalid chat payload"))
            }

            val peer = presenceManager.getPeerByUsername(dst)
            val targetNodeId = if (peer != null) {
                if (peer.activeTransport == TransportMode.WIFI) peer.wifiNodeId else peer.btNodeId
            } else {
                dst // fallback to username
            }

            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            val activeMode = identityRepository.getTransportMode().first()
            val ownNodeId = identityRepository.getActiveNodeId(activeMode)

            val packet = Packet(
                id = messageId,
                type = PacketType.CHAT,
                senderId = ownNodeId,
                targetId = targetNodeId,
                payload = payload.data,
                timestamp = timestamp,
                hopCount = 0
            )

            val convoId = generateConversationId(ownIdentity.username, dst)

            val localMessage = Message(
                id = messageId,
                text = text,
                senderId = ownIdentity.username,
                senderName = ownName,
                timestamp = timestamp,
                isOutgoing = true,
                status = MessageStatus.SENT,
                hopCount = 0,
                conversationId = convoId
            )
            writeChannel.send(MessageEntity.fromDomain(localMessage))

            meshNode?.sendPacket(packet)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send direct message")
            Result.failure(e)
        }
    }

    override suspend fun sendMessage(dst: String, payload: EncryptedPacket): Result<Unit> {
        val wrappedPayload = ChatPayload(dst = dst, text = cryptoRepository.decryptMessage(payload).getOrNull()?.text ?: "Message (Encrypted)").encode()
        return sendMessage(cryptoRepository.encryptMessage(wrappedPayload))
    }

    override suspend fun sendBroadcast(payload: EncryptedPacket): Result<Unit> {
        return try {
            val ownIdentity = identityRepository.observeIdentity().first()
            val ownName = ownIdentity.username.ifEmpty { "Me" }

            val decryptedResult = cryptoRepository.decryptMessage(payload)
            val text = decryptedResult.getOrNull()?.text ?: "Message (Encrypted)"
            val roomId = decryptedResult.getOrNull()?.roomId ?: "MESH0"

            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            val activeMode = identityRepository.getTransportMode().first()
            val ownNodeId = identityRepository.getActiveNodeId(activeMode)

            val packet = Packet(
                id = messageId,
                type = PacketType.BROADCAST,
                senderId = ownNodeId,
                targetId = null,
                payload = payload.data,
                timestamp = timestamp,
                hopCount = 0
            )

            val localMessage = Message(
                id = messageId,
                text = text,
                senderId = ownIdentity.username,
                senderName = ownName,
                timestamp = timestamp,
                isOutgoing = true,
                status = MessageStatus.SENT,
                hopCount = 0,
                conversationId = "broadcast_$roomId"
            )
            writeChannel.send(MessageEntity.fromDomain(localMessage))

            meshNode?.sendPacket(packet)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send broadcast")
            Result.failure(e)
        }
    }

    override fun observeConversation(id: String): Flow<List<Message>> {
        return messageDao.observeConversation(id).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeAllConversations(): Flow<List<Conversation>> {
        return combine(
            messageDao.observeAllMessages(),
            presenceManager.peers,
            identityRepository.observeIdentity()
        ) { messages, peers, ownIdentity ->
            val myUsername = ownIdentity.username
            messages.groupBy { it.conversationId }.map { (convoId, msgs) ->
                val lastMsg = msgs.maxByOrNull { it.timestamp }
                val peerUsername = if (convoId.startsWith("broadcast_")) {
                    ""
                } else {
                    convoId.substringAfter("dm_")
                        .split("_")
                        .firstOrNull { it != myUsername } ?: ""
                }
                val peerName = if (convoId.startsWith("broadcast_")) {
                    val room = convoId.substringAfter("broadcast_")
                    "Room: $room"
                } else {
                    peers[peerUsername]?.username ?: lastMsg?.senderName ?: peerUsername
                }
                Conversation(
                    id = convoId,
                    peerId = if (convoId.startsWith("broadcast_")) convoId else peerUsername,
                    peerName = peerName,
                    lastMessage = lastMsg?.text ?: "",
                    lastMessageTime = lastMsg?.timestamp ?: 0L,
                    unreadCount = 0
                )
            }
        }
    }

    override fun observeBroadcasts(): Flow<List<Message>> {
        return messageDao.observeAllMessages().map { messages ->
            messages.filter { it.conversationId.startsWith("broadcast_") }
                .map { it.toDomain() }
        }
    }

    override suspend fun markConversationRead(conversationId: String) {
        // No-op
    }

    override suspend fun clearAllMessages() {
        messageDao.clearAllMessages()
    }

    override suspend fun updateMediaTransfer(transfer: com.meshchat.domain.model.MediaTransfer) {
        val statusName = when (transfer.status) {
            com.meshchat.domain.model.MediaTransferStatus.Pending -> "PENDING"
            is com.meshchat.domain.model.MediaTransferStatus.Progress -> "PROGRESS"
            com.meshchat.domain.model.MediaTransferStatus.Verifying -> "VERIFYING"
            com.meshchat.domain.model.MediaTransferStatus.Complete -> "COMPLETE"
            is com.meshchat.domain.model.MediaTransferStatus.Failed -> "FAILED"
            com.meshchat.domain.model.MediaTransferStatus.Cancelled -> "CANCELLED"
        }
        messageDao.updateMediaStatus(transfer.transferId, statusName, transfer.localUri)
    }

    override suspend fun insertMediaMessage(message: Message) {
        messageDao.insertMedia(MessageEntity.fromDomain(message))
    }

    private fun generateConversationId(user1: String, user2: String): String {
        return "dm_${user1.lowercase().trim()}_${user2.lowercase().trim()}"
            .split("_").sorted().joinToString("_")
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private suspend fun <T> ReceiveChannel<T>.batch(
        maxSize: Int, timeoutMs: Long
    ): Flow<List<T>> = flow {
        while (!isClosedForReceive) {
            val batch = mutableListOf<T>()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (batch.size < maxSize) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val item = runCatching {
                    withTimeout(remaining) { receive() }
                }.getOrNull() ?: break
                batch.add(item)
            }
            if (batch.isNotEmpty()) emit(batch)
        }
    }
}

