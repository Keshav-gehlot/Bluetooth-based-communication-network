package com.meshchat.data.media

import android.net.Uri
import com.meshchat.core.*
import com.meshchat.data.database.MessageDao
import com.meshchat.data.database.MessageEntity
import com.meshchat.domain.model.MediaTransfer
import com.meshchat.domain.model.MediaTransferStatus
import com.meshchat.domain.model.MessageStatus
import com.meshchat.domain.repository.CryptoRepository
import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaTransferEngine @Inject constructor(
    private val processor: MediaProcessor,
    private val crypto: CryptoRepository,
    private val fileStorage: MediaFileStorage,
    private val identityRepository: IdentityRepository,
    private val messageDao: MessageDao,
) {
    private var meshNode: MeshNode? = null

    // Active outgoing transfers
    private val outgoing = ConcurrentHashMap<String, OutgoingTransfer>()

    // Active incoming transfers
    private val incoming = ConcurrentHashMap<String, IncomingTransfer>()

    private val json = Json { ignoreUnknownKeys = true }

    private val _transferFlow = MutableSharedFlow<MediaTransfer>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transferFlow: Flow<MediaTransfer> = _transferFlow.asSharedFlow()

    fun setMeshNode(node: MeshNode) {
        this.meshNode = node
    }

    fun start(scope: CoroutineScope) {
        val node = meshNode ?: return
        scope.launch {
            node.receivedPackets.collect { packet ->
                if (packet.type == PacketType.MEDIA_HEADER ||
                    packet.type == PacketType.MEDIA_CHUNK ||
                    packet.type == PacketType.MEDIA_CHUNK_ACK ||
                    packet.type == PacketType.MEDIA_EOF ||
                    packet.type == PacketType.MEDIA_ACK ||
                    packet.type == PacketType.MEDIA_CANCEL
                ) {
                    onPacket(packet)
                }
            }
        }
    }

    // ─── SEND ──────────────────────────────────────────────────
    suspend fun sendMedia(
        uri: Uri,
        mediaType: MediaType,
        dstUsername: String,
        mode: TransportMode,
        key: ByteArray,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val node = meshNode ?: throw IllegalStateException("MeshNode not initialized")

            // 1. Validate size/compress
            val data = when (mediaType) {
                MediaType.IMAGE -> processor.compressImage(uri).getOrThrow()
                MediaType.VIDEO -> {
                    if (!MediaLimits.isVideoAllowed(mode)) {
                        throw IllegalStateException(
                            "Video not supported over Bluetooth. Switch to WiFi."
                        )
                    }
                    processor.validateVideo(uri).getOrThrow()
                }
            }

            val sizeLimit = when {
                mediaType == MediaType.VIDEO -> MediaLimits.VIDEO_MAX_BYTES
                mode == TransportMode.BLUETOOTH -> MediaLimits.IMAGE_MAX_BYTES_BT
                else -> MediaLimits.IMAGE_MAX_BYTES_WIFI
            }
            if (data.size > sizeLimit) {
                throw IllegalArgumentException(
                    "File too large: ${data.size / 1024}KB limit ${sizeLimit / 1024}KB"
                )
            }

            // 2. Prepare
            val transferId = UUID.randomUUID().toString()
            val thumbnail = processor.generateThumbnail(data, mediaType)
            val checksum = processor.sha256(data)
            val chunkSize = ChunkSizes.forMode(mode)
            val chunks = processor.chunkAndEncrypt(data, chunkSize, key, transferId)

            val identity = identityRepository.observeIdentity().first()
            val localUsername = identity.username
            val conversationId = buildConversationId(localUsername, dstUsername)

            // Insert placeholder message in Room
            val messageEntity = MessageEntity(
                id = transferId,
                text = null,
                senderId = localUsername,
                senderName = localUsername,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                status = MessageStatus.SENT.name,
                hopCount = 0,
                conversationId = conversationId,
                mediaTransferId = transferId,
                mediaType = mediaType.name,
                mediaThumbnailBase64 = thumbnail,
                mediaLocalPath = uri.toString(),
                mediaSizeBytes = data.size.toLong(),
                mediaStatus = "PROGRESS"
            )
            messageDao.insertMedia(messageEntity)

            // 3. Send HEADER
            node.sendPacket(
                Packet(
                    id = UUID.randomUUID().toString(),
                    type = PacketType.MEDIA_HEADER,
                    senderId = localUsername,
                    targetId = dstUsername,
                    payload = json.encodeToString(MediaHeaderPayload(
                        transferId = transferId,
                        mediaType = mediaType,
                        filename = "media_${transferId.take(8)}.${if (mediaType == MediaType.IMAGE) "jpg" else "mp4"}",
                        mimeType = if (mediaType == MediaType.IMAGE) "image/jpeg" else "video/mp4",
                        totalSizeBytes = data.size.toLong(),
                        totalChunks = chunks.size,
                        chunkSizeBytes = chunkSize,
                        sha256Checksum = checksum,
                        thumbnailBase64 = thumbnail,
                        senderUsername = localUsername,
                        conversationId = conversationId,
                        timestamp = System.currentTimeMillis()
                    )).encodeToByteArray(),
                    timestamp = System.currentTimeMillis()
                )
            )

            // 4. Send chunks WITH backpressure
            val transfer = OutgoingTransfer(
                transferId = transferId,
                chunks = chunks,
                chunkAckChannel = Channel(capacity = 1) // BT backpressure: capacity = 1
            )
            outgoing[transferId] = transfer

            for ((index, chunk) in chunks.withIndex()) {
                if (transfer.cancelled) break

                // Update progress in Room
                val percent = (index * 100) / chunks.size
                messageDao.updateMediaStatus(transferId, "PROGRESS", uri.toString())

                // Emit progress
                _transferFlow.tryEmit(MediaTransfer(
                    transferId = transferId,
                    mediaType = mediaType,
                    mimeType = if (mediaType == MediaType.IMAGE) "image/jpeg" else "video/mp4",
                    totalSizeBytes = data.size.toLong(),
                    bytesTransferred = (index * chunkSize).toLong().coerceAtMost(data.size.toLong()),
                    status = MediaTransferStatus.Progress(
                        percent = percent,
                        chunksReceived = index,
                        totalChunks = chunks.size,
                    ),
                    localUri = uri.toString(),
                    thumbnailBase64 = thumbnail,
                    senderUsername = localUsername,
                    conversationId = conversationId,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = true,
                ))

                // Send chunk
                node.sendPacket(
                    Packet(
                        id = UUID.randomUUID().toString(),
                        type = PacketType.MEDIA_CHUNK,
                        senderId = localUsername,
                        targetId = dstUsername,
                        payload = json.encodeToString(chunk).encodeToByteArray(),
                        timestamp = System.currentTimeMillis()
                    )
                )

                // BACKPRESSURE: wait for CHUNK_ACK before sending next
                // (skip on WiFi — only strict on BT to prevent buffer overflow)
                if (mode == TransportMode.BLUETOOTH) {
                    withTimeout(10_000L) {
                        transfer.chunkAckChannel.receive() // blocks until ACK
                    }
                }
            }

            // 5. Send EOF
            if (!transfer.cancelled) {
                node.sendPacket(
                    Packet(
                        id = UUID.randomUUID().toString(),
                        type = PacketType.MEDIA_EOF,
                        senderId = localUsername,
                        targetId = dstUsername,
                        payload = json.encodeToString(MediaEofPayload(
                            transferId = transferId,
                            checksum = checksum
                        )).encodeToByteArray(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            outgoing.remove(transferId)
            transferId
        }
    }

    fun cancelOutgoing(transferId: String) {
        outgoing[transferId]?.cancelled = true
        outgoing.remove(transferId)
        
        CoroutineScope(Dispatchers.IO).launch {
            messageDao.updateMediaStatus(transferId, "CANCELLED", null)
            val node = meshNode ?: return@launch
            val identity = identityRepository.observeIdentity().first()
            node.sendPacket(
                Packet(
                    id = UUID.randomUUID().toString(),
                    type = PacketType.MEDIA_CANCEL,
                    senderId = identity.username,
                    targetId = null, // broadcast cancellation
                    payload = json.encodeToString(MediaCancelPayload(transferId)).encodeToByteArray(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    // ─── RECEIVE ───────────────────────────────────────────────
    private suspend fun onPacket(packet: Packet) {
        val key = crypto.getCurrentKey() ?: return

        when (packet.type) {
            PacketType.MEDIA_HEADER -> {
                val header = json.decodeFromString<MediaHeaderPayload>(String(packet.payload))
                onHeaderReceived(header)
            }
            PacketType.MEDIA_CHUNK -> {
                val chunk = json.decodeFromString<MediaChunkPayload>(String(packet.payload))
                onChunkReceived(chunk, key)
            }
            PacketType.MEDIA_CHUNK_ACK -> {
                val ack = json.decodeFromString<MediaChunkAckPayload>(String(packet.payload))
                outgoing[ack.transferId]?.chunkAckChannel?.trySend(ack.ackIndex)
            }
            PacketType.MEDIA_EOF -> {
                val eof = json.decodeFromString<MediaEofPayload>(String(packet.payload))
                onEofReceived(eof.transferId, eof.checksum, key)
            }
            PacketType.MEDIA_ACK -> {
                val ack = json.decodeFromString<MediaAckPayload>(String(packet.payload))
                messageDao.updateMediaStatus(ack.transferId, "COMPLETE", null)
            }
            PacketType.MEDIA_CANCEL -> {
                val cancel = json.decodeFromString<MediaCancelPayload>(String(packet.payload))
                incoming[cancel.transferId]?.let {
                    messageDao.updateMediaStatus(cancel.transferId, "CANCELLED", null)
                    fileStorage.deleteTempFile(cancel.transferId)
                    incoming.remove(cancel.transferId)
                }
                outgoing[cancel.transferId]?.let {
                    it.cancelled = true
                    messageDao.updateMediaStatus(cancel.transferId, "CANCELLED", null)
                    outgoing.remove(cancel.transferId)
                }
            }
            else -> {}
        }
    }

    private suspend fun onHeaderReceived(header: MediaHeaderPayload) {
        val tmpFile = fileStorage.createTempFile(header.transferId)
        incoming[header.transferId] = IncomingTransfer(
            header = header,
            tmpFile = tmpFile,
            receivedChunks = java.util.Collections.synchronizedList(mutableListOf()),
            expectedChunks = header.totalChunks
        )

        // Insert placeholder message in Room
        val messageEntity = MessageEntity(
            id = header.transferId,
            text = null,
            senderId = header.senderUsername,
            senderName = header.senderUsername,
            timestamp = header.timestamp,
            isOutgoing = false,
            status = MessageStatus.DELIVERED.name,
            hopCount = 1,
            conversationId = header.conversationId,
            mediaTransferId = header.transferId,
            mediaType = header.mediaType.name,
            mediaThumbnailBase64 = header.thumbnailBase64,
            mediaLocalPath = null,
            mediaSizeBytes = header.totalSizeBytes,
            mediaStatus = "PENDING"
        )
        messageDao.insertMedia(messageEntity)

        _transferFlow.tryEmit(MediaTransfer(
            transferId = header.transferId,
            mediaType = header.mediaType,
            mimeType = header.mimeType,
            totalSizeBytes = header.totalSizeBytes,
            bytesTransferred = 0L,
            status = MediaTransferStatus.Pending,
            localUri = null,
            thumbnailBase64 = header.thumbnailBase64,
            senderUsername = header.senderUsername,
            conversationId = header.conversationId,
            timestamp = header.timestamp,
            isOutgoing = false
        ))
    }

    private fun onChunkReceived(chunk: MediaChunkPayload, key: ByteArray) {
        val transfer = incoming[chunk.transferId] ?: return

        // Decrypt using MediaProcessor helper
        val decrypted = processor.decryptChunk(chunk, key) ?: return

        // Write chunk to temp file using FileChannel / RandomAccessFile for position support
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RandomAccessFile(transfer.tmpFile, "rw").use { raf ->
                    val fc = raf.channel
                    val buffer = java.nio.ByteBuffer.wrap(decrypted)
                    fc.write(buffer, chunk.chunkIndex.toLong() * transfer.header.chunkSizeBytes)
                }

                if (!transfer.receivedChunks.contains(chunk.chunkIndex)) {
                    transfer.receivedChunks.add(chunk.chunkIndex)
                }

                // Send CHUNK_ACK back to sender (backpressure signal)
                val node = meshNode
                if (node != null) {
                    val identity = identityRepository.observeIdentity().first()
                    node.sendPacket(
                        Packet(
                            id = UUID.randomUUID().toString(),
                            type = PacketType.MEDIA_CHUNK_ACK,
                            senderId = identity.username,
                            targetId = transfer.header.senderUsername,
                            payload = json.encodeToString(MediaChunkAckPayload(
                                transferId = chunk.transferId,
                                ackIndex = chunk.chunkIndex
                            )).encodeToByteArray(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                // Emit progress
                val progress = (transfer.receivedChunks.size * 100) / transfer.expectedChunks
                messageDao.updateMediaStatus(chunk.transferId, "PROGRESS", null)
                _transferFlow.tryEmit(transfer.toProgressEvent(progress))
            } catch (e: Exception) {
                Timber.e(e, "Error writing media chunk index ${chunk.chunkIndex}")
            }
        }
    }

    private suspend fun onEofReceived(transferId: String, checksum: String, key: ByteArray) {
        val transfer = incoming[transferId] ?: return

        // Emit verifying state
        messageDao.updateMediaStatus(transferId, "VERIFYING", null)
        _transferFlow.tryEmit(transfer.toVerifyingEvent())

        // SHA-256 whole file
        val fileBytes = withContext(Dispatchers.IO) {
            transfer.tmpFile.readBytes()
        }
        val actualChecksum = processor.sha256(fileBytes)

        if (actualChecksum != checksum) {
            messageDao.updateMediaStatus(transferId, "FAILED", null)
            _transferFlow.tryEmit(transfer.toFailedEvent("Checksum mismatch — file corrupted"))
            fileStorage.deleteTempFile(transferId)
            incoming.remove(transferId)
            return
        }

        // Move temp → permanent
        val finalUri = withContext(Dispatchers.IO) {
            fileStorage.savePermanent(
                transferId = transferId,
                data = fileBytes,
                mediaType = transfer.header.mediaType,
                filename = transfer.header.filename
            )
        }

        // Send MEDIA_ACK to sender
        val node = meshNode
        if (node != null) {
            val identity = identityRepository.observeIdentity().first()
            node.sendPacket(
                Packet(
                    id = UUID.randomUUID().toString(),
                    type = PacketType.MEDIA_ACK,
                    senderId = identity.username,
                    targetId = transfer.header.senderUsername,
                    payload = json.encodeToString(MediaAckPayload(transferId = transferId)).encodeToByteArray(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        messageDao.updateMediaStatus(transferId, "COMPLETE", finalUri)
        _transferFlow.tryEmit(transfer.toCompleteEvent(finalUri))
        incoming.remove(transferId)
        fileStorage.deleteTempFile(transferId)
    }

    private fun buildConversationId(user1: String, user2: String): String {
        return if (user1 < user2) "dm_${user1}_${user2}" else "dm_${user2}_${user1}"
    }

    private fun IncomingTransfer.toProgressEvent(progress: Int): MediaTransfer {
        return MediaTransfer(
            transferId = header.transferId,
            mediaType = header.mediaType,
            mimeType = header.mimeType,
            totalSizeBytes = header.totalSizeBytes,
            bytesTransferred = (receivedChunks.size * header.chunkSizeBytes).toLong().coerceAtMost(header.totalSizeBytes),
            status = MediaTransferStatus.Progress(
                percent = progress,
                chunksReceived = receivedChunks.size,
                totalChunks = expectedChunks
            ),
            localUri = null,
            thumbnailBase64 = header.thumbnailBase64,
            senderUsername = header.senderUsername,
            conversationId = header.conversationId,
            timestamp = header.timestamp,
            isOutgoing = false
        )
    }

    private fun IncomingTransfer.toVerifyingEvent(): MediaTransfer {
        return MediaTransfer(
            transferId = header.transferId,
            mediaType = header.mediaType,
            mimeType = header.mimeType,
            totalSizeBytes = header.totalSizeBytes,
            bytesTransferred = header.totalSizeBytes,
            status = MediaTransferStatus.Verifying,
            localUri = null,
            thumbnailBase64 = header.thumbnailBase64,
            senderUsername = header.senderUsername,
            conversationId = header.conversationId,
            timestamp = header.timestamp,
            isOutgoing = false
        )
    }

    private fun IncomingTransfer.toFailedEvent(reason: String): MediaTransfer {
        return MediaTransfer(
            transferId = header.transferId,
            mediaType = header.mediaType,
            mimeType = header.mimeType,
            totalSizeBytes = header.totalSizeBytes,
            bytesTransferred = 0L,
            status = MediaTransferStatus.Failed(reason),
            localUri = null,
            thumbnailBase64 = header.thumbnailBase64,
            senderUsername = header.senderUsername,
            conversationId = header.conversationId,
            timestamp = header.timestamp,
            isOutgoing = false
        )
    }

    private fun IncomingTransfer.toCompleteEvent(localPath: String): MediaTransfer {
        return MediaTransfer(
            transferId = header.transferId,
            mediaType = header.mediaType,
            mimeType = header.mimeType,
            totalSizeBytes = header.totalSizeBytes,
            bytesTransferred = header.totalSizeBytes,
            status = MediaTransferStatus.Complete,
            localUri = localPath,
            thumbnailBase64 = header.thumbnailBase64,
            senderUsername = header.senderUsername,
            conversationId = header.conversationId,
            timestamp = header.timestamp,
            isOutgoing = false
        )
    }

    private data class OutgoingTransfer(
        val transferId: String,
        val chunks: List<MediaChunkPayload>,
        val chunkAckChannel: Channel<Int>,
        var cancelled: Boolean = false,
    )

    private data class IncomingTransfer(
        val header: MediaHeaderPayload,
        val tmpFile: File,
        val receivedChunks: MutableList<Int>,
        val expectedChunks: Int,
    )
}
