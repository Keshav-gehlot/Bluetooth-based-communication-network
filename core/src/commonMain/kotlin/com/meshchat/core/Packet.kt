package com.meshchat.core

import kotlinx.serialization.Serializable

@Serializable
enum class PacketType {
    CHAT, BROADCAST, ANNOUNCEMENT, SYSTEM
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
