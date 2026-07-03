@file:Suppress("PLUGIN_ERROR", "SERIALIZER_NOT_FOUND")

package com.meshchat.core

import kotlinx.serialization.Serializable

@Serializable
data class MediaHeaderPayload(
    val transferId: String,
    val mediaType: MediaType,
    val filename: String,
    val mimeType: String,
    val totalSizeBytes: Long,
    val totalChunks: Int,
    val chunkSizeBytes: Int,
    val sha256Checksum: String,
    val thumbnailBase64: String,
    val senderUsername: String,
    val conversationId: String,
    val timestamp: Long
)

@Serializable
data class MediaChunkPayload(
    val transferId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val data: String, // base64 encoded encrypted chunk bytes
    val chunkHmac: String
)

@Serializable
data class MediaChunkAckPayload(
    val transferId: String,
    val ackIndex: Int
)

@Serializable
data class MediaEofPayload(
    val transferId: String,
    val checksum: String
)

@Serializable
data class MediaAckPayload(
    val transferId: String
)

@Serializable
data class MediaCancelPayload(
    val transferId: String
)
