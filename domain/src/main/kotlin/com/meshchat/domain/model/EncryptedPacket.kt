package com.meshchat.domain.model

data class EncryptedPacket(
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedPacket
        return data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}
