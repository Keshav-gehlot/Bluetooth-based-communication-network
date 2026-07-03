package com.meshchat.core

import kotlinx.serialization.Serializable

@Serializable
enum class PacketType {
    CHAT, BROADCAST, ANNOUNCEMENT, SYSTEM,
    CONTROL, METRICS,
    PRESENCE,           // T-7: richer presence with dual IDs
    DELIVERY_ACK,       // message delivery acknowledgement
    TYPING,             // typing indicator
    USERNAME_CLAIM,     // T-2: "I want this username"
    USERNAME_CONFLICT,  // T-2: "That username is taken"
    USERNAME_REGISTRY,  // T-2: gossip — here is my known registry
    USERNAME_RELEASE,   // T-2: user changed name, releasing old one
    MEDIA_HEADER,       // M-1: announces a new media transfer (metadata)
    MEDIA_CHUNK,        // M-1: carries an encrypted segment of the media
    MEDIA_CHUNK_ACK,    // M-1: acknowledges segment receipt (used for backpressure)
    MEDIA_EOF,          // M-1: signals transfer end from sender
    MEDIA_ACK,          // M-1: confirms whole-file verification by receiver
    MEDIA_CANCEL,       // M-1: cancels transfer
    MEDIA_AVAILABLE,    // M-1: gossip notification
    VOICE_START,        // V-1: sender begins PTT — init decoder on receiver
    VOICE_FRAME,        // V-1: one 20ms Opus-encoded audio frame
    VOICE_END,          // V-1: sender releases PTT — drain jitter buffer
    VOICE_BUSY,         // V-1: peer is already transmitting — reject new PTT
}


@Serializable
data class Packet(
    val id: String,
    val type: PacketType,
    val senderId: String,
    val targetId: String? = null, // null means broadcast
    val payload: ByteArray,
    val timestamp: Long,
    val hopCount: Int = 0,
    val signature: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Packet

        if (id != other.id) return false
        if (type != other.type) return false
        if (senderId != other.senderId) return false
        if (targetId != other.targetId) return false
        if (!payload.contentEquals(other.payload)) return false
        if (timestamp != other.timestamp) return false
        if (hopCount != other.hopCount) return false
        if (signature != null) {
            if (other.signature == null) return false
            if (!signature.contentEquals(other.signature)) return false
        } else if (other.signature != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + (targetId?.hashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}
