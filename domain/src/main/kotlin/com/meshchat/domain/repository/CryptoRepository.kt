package com.meshchat.domain.repository

import com.meshchat.domain.model.EncryptedPacket
import com.meshchat.domain.model.DecryptedPayload

interface CryptoRepository {
    fun encryptMessage(text: String, roomId: String? = null): EncryptedPacket
    fun decryptMessage(packet: EncryptedPacket): Result<DecryptedPayload>
    suspend fun joinRoom(newCode: String)
}
