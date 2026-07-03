package com.meshchat.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meshchat.core.MediaType
import com.meshchat.domain.model.Message
import com.meshchat.domain.model.MessageStatus

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val text: String?,              // null for media-only messages
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: String,
    val hopCount: Int,
    val conversationId: String,
    val mediaTransferId: String?,   // non-null for media messages
    val mediaType: String?,         // "IMAGE" | "VIDEO" | null
    val mediaThumbnailBase64: String?, // always stored for quick display
    val mediaLocalPath: String?,    // null until transfer completes
    val mediaSizeBytes: Long,
    val mediaStatus: String         // mirrors MediaTransferStatus name
) {
    fun toDomain(): Message {
        return Message(
            id = id,
            text = text ?: "",
            senderId = senderId,
            senderName = senderName,
            timestamp = timestamp,
            isOutgoing = isOutgoing,
            status = MessageStatus.valueOf(status),
            hopCount = hopCount,
            conversationId = conversationId,
            mediaTransferId = mediaTransferId,
            mediaType = mediaType?.let { MediaType.valueOf(it) },
            mediaThumbnailBase64 = mediaThumbnailBase64,
            mediaLocalPath = mediaLocalPath,
            mediaSizeBytes = mediaSizeBytes,
            mediaStatus = mediaStatus
        )
    }

    companion object {
        fun fromDomain(domain: Message): MessageEntity {
            return MessageEntity(
                id = domain.id,
                text = domain.text.takeIf { it.isNotEmpty() },
                senderId = domain.senderId,
                senderName = domain.senderName,
                timestamp = domain.timestamp,
                isOutgoing = domain.isOutgoing,
                status = domain.status.name,
                hopCount = domain.hopCount,
                conversationId = domain.conversationId,
                mediaTransferId = domain.mediaTransferId,
                mediaType = domain.mediaType?.name,
                mediaThumbnailBase64 = domain.mediaThumbnailBase64,
                mediaLocalPath = domain.mediaLocalPath,
                mediaSizeBytes = domain.mediaSizeBytes,
                mediaStatus = domain.mediaStatus
            )
        }
    }
}
