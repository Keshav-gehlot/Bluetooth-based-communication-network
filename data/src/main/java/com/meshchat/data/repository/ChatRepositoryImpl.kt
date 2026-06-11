package com.meshchat.data.repository

import com.meshchat.core.MeshNode
import com.meshchat.core.NodeConfig
import com.meshchat.core.Packet
import com.meshchat.core.PacketType
import com.meshchat.core.PresenceManager
import com.meshchat.core.TransportAdapter
import com.meshchat.domain.model.ChatPayload
import com.meshchat.data.database.MessageDao
import com.meshchat.data.database.MessageEntity
import com.meshchat.domain.model.Conversation
import com.meshchat.domain.model.EncryptedPacket
import com.meshchat.domain.model.Message
import com.meshchat.domain.model.MessageStatus
import com.meshchat.domain.repository.ChatRepository
import com.meshchat.domain.repository.CryptoRepository
import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
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
    private val cryptoRepository: CryptoRepository
) : ChatRepository {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var meshNode: MeshNode? = null
    private val isMeshStarted = MutableStateFlow(false)
    private val writeChannel = Channel<MessageEntity>(capacity = 256)

    init {
        scope.launch(Dispatchers.IO) {
            writeChannel.batch(maxSize = 20, timeoutMs = 100).collect { batch ->
                runCatching { messageDao.insertAll(batch) }
                    .onFailure { Timber.e(it, "Batch insert failed") }
            }
        }

        scope.launch {
            identityRepository.observeIdentity().collect { identity ->
                if (identity.nodeId.isNotEmpty() && !isMeshStarted.value) {
                    isMeshStarted.value = true
                    Timber.d("Starting MeshNode with nodeId: ${identity.nodeId}")
                    
                    // Start the physical transport layer (Nearby Connections)
                    transport.start(identity.nodeId)

                    val savedMaxHops = identityRepository.getMaxHops().first()
                    val savedIdsEnabled = identityRepository.getIdsEnabled().first()

                    // Instantiate the MeshNode router
                    val node = MeshNode(
                        nodeId = identity.nodeId,
                        config = NodeConfig(
                            maxHops = savedMaxHops,
                            idsEnabled = savedIdsEnabled
                        ),
                        transport = transport
                    )
                    meshNode = node
                    node.start(scope)

                    // Listen to incoming packets routed through the MeshNode
                    node.receivedPackets.collect { packet ->
                        handleIncomingPacket(packet, identity.nodeId)
                    }
                }
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: Packet, ownNodeId: String) {
        val peersMap = presenceManager.peers.value
        val senderName = peersMap[packet.senderId]?.displayName ?: "Unknown Peer"

        when (packet.type) {
            PacketType.CHAT -> {
                if (packet.targetId == ownNodeId) {
                    val decryptedResult = cryptoRepository.decryptMessage(EncryptedPacket(packet.payload))
                    if (decryptedResult.isSuccess) {
                        val text = decryptedResult.getOrThrow().text
                        val message = Message(
                            id = packet.id,
                            text = text,
                            senderId = packet.senderId,
                            senderName = senderName,
                            timestamp = packet.timestamp,
                            isOutgoing = false,
                            status = MessageStatus.DELIVERED,
                            hopCount = packet.hopCount,
                            conversationId = packet.senderId
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
                        senderId = packet.senderId,
                        senderName = senderName,
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
            else -> {}
        }
    }

    override suspend fun sendMessage(payload: EncryptedPacket): Result<Unit> {
        return try {
            val ownIdentity = identityRepository.observeIdentity().first()
            val ownName = ownIdentity.displayName.ifEmpty { "Me" }
            
            // Decrypt payload locally to save to local database and recover the destination.
            val decryptedResult = cryptoRepository.decryptMessage(payload)
            val payloadText = decryptedResult.getOrNull()?.text ?: "Message (Encrypted)"
            val chatPayload = ChatPayload.decode(payloadText)
            val dst = chatPayload?.dst ?: ""
            val text = chatPayload?.text ?: payloadText

            if (dst.isBlank()) {
                return Result.failure(IllegalArgumentException("Invalid chat payload"))
            }

            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            val packet = Packet(
                id = messageId,
                type = PacketType.CHAT,
                senderId = ownIdentity.nodeId,
                targetId = dst,
                payload = payload.data,
                timestamp = timestamp,
                hopCount = 0
            )

            val localMessage = Message(
                id = messageId,
                text = text,
                senderId = ownIdentity.nodeId,
                senderName = ownName,
                timestamp = timestamp,
                isOutgoing = true,
                status = MessageStatus.SENT,
                hopCount = 0,
                conversationId = dst
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
            val ownName = ownIdentity.displayName.ifEmpty { "Me" }

            val decryptedResult = cryptoRepository.decryptMessage(payload)
            val text = decryptedResult.getOrNull()?.text ?: "Message (Encrypted)"
            val roomId = decryptedResult.getOrNull()?.roomId ?: "MESH0"

            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            val packet = Packet(
                id = messageId,
                type = PacketType.BROADCAST,
                senderId = ownIdentity.nodeId,
                targetId = null,
                payload = payload.data,
                timestamp = timestamp,
                hopCount = 0
            )

            val localMessage = Message(
                id = messageId,
                text = text,
                senderId = ownIdentity.nodeId,
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
            presenceManager.peers
        ) { messages, peers ->
            messages.groupBy { it.conversationId }.map { (convoId, msgs) ->
                val lastMsg = msgs.maxByOrNull { it.timestamp }
                val peerName = if (convoId.startsWith("broadcast_")) {
                    val room = convoId.substringAfter("broadcast_")
                    "Room: $room"
                } else {
                    peers[convoId]?.displayName ?: lastMsg?.senderName ?: "Unknown Peer"
                }
                Conversation(
                    id = convoId,
                    peerId = convoId,
                    peerName = peerName,
                    lastMessage = lastMsg?.text ?: "",
                    lastMessageTime = lastMsg?.timestamp ?: 0L,
                    unreadCount = 0
                )
            }
        }
    }
    override fun observeBroadcasts(): Flow<List<Message>> {
        // Note: For observing broadcasts, this should ideally accept a roomId.
        // For backwards compatibility or default, we just get MESH0 or all broadcasts.
        // We will observe all messages that start with "broadcast_"
        return messageDao.observeAllMessages().map { messages ->
            messages.filter { it.conversationId.startsWith("broadcast_") }
                .map { it.toDomain() }
        }
    }

    override suspend fun markConversationRead(conversationId: String) {
        // No-op or update read state in DB if needed
    }

    override suspend fun clearAllMessages() {
        messageDao.clearAllMessages()
    }

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
