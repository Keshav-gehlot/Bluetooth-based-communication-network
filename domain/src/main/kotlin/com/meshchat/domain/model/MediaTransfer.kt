package com.meshchat.domain.model

import com.meshchat.core.MediaType

data class MediaTransfer(
    val transferId: String,
    val mediaType: MediaType,
    val mimeType: String,
    val totalSizeBytes: Long,
    val bytesTransferred: Long,
    val status: MediaTransferStatus,
    val localUri: String?,
    val thumbnailBase64: String,
    val senderUsername: String,
    val conversationId: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
)

sealed class MediaTransferStatus {
    object Pending : MediaTransferStatus()
    data class Progress(
        val percent: Int,
        val chunksReceived: Int,
        val totalChunks: Int,
    ) : MediaTransferStatus()
    object Verifying : MediaTransferStatus()
    object Complete : MediaTransferStatus()
    data class Failed(val reason: String) : MediaTransferStatus()
    object Cancelled : MediaTransferStatus()
}
