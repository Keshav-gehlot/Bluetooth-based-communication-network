package com.meshchat.domain.model

enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

data class Message(
    val id: String,
    val text: String,
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: MessageStatus,
    val hopCount: Int,
    val conversationId: String,
    val mediaTransferId: String? = null,
    val mediaType: com.meshchat.core.MediaType? = null,
    val mediaThumbnailBase64: String? = null,
    val mediaLocalPath: String? = null,
    val mediaSizeBytes: Long = 0L,
    val mediaStatus: String = "",
)
