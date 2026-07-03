package com.meshchat.data.repository

import com.meshchat.core.PlatformCrypto
import com.meshchat.data.security.RoomCodeManager
import com.meshchat.domain.model.EncryptedPacket
import com.meshchat.domain.model.DecryptedPayload
import com.meshchat.domain.repository.CryptoRepository
import timber.log.Timber
import javax.inject.Inject

class CryptoRepositoryImpl @Inject constructor(
    private val roomCodeManager: RoomCodeManager
) : CryptoRepository {

    private val platformCrypto = PlatformCrypto()

    override fun encryptMessage(text: String, roomId: String?): EncryptedPacket {
        Timber.d("Encrypting message of length ${text.length}")
        val dataBytes = text.toByteArray(Charsets.UTF_8)
        
        val targetCode = roomId ?: roomCodeManager.joinedRooms.value.firstOrNull()
        val meshKey = if (targetCode != null) roomCodeManager.getDerivedMeshKey(targetCode) else null

        val encryptedData = if (meshKey != null) {
            try {
                platformCrypto.encrypt(dataBytes, meshKey)
            } catch (e: Exception) {
                Timber.e(e, "Encryption failed, falling back to raw data")
                dataBytes
            }
        } else {
            Timber.w("No mesh key configured, using plaintext encoding as fallback")
            dataBytes
        }
        return EncryptedPacket(encryptedData)
    }

    override fun decryptMessage(packet: EncryptedPacket): Result<DecryptedPayload> {
        val keys = roomCodeManager.getAllMeshKeys()
        
        if (keys.isEmpty()) {
            Timber.w("No mesh keys configured, using plaintext decoding as fallback")
            return Result.success(DecryptedPayload(String(packet.data, Charsets.UTF_8), "MESH0"))
        }

        for ((code, meshKey) in keys) {
            try {
                val decryptedData = platformCrypto.decrypt(packet.data, meshKey)
                return Result.success(DecryptedPayload(String(decryptedData, Charsets.UTF_8), code))
            } catch (e: Exception) {
                continue
            }
        }
        
        Timber.e("Decryption failed for all known keys.")
        return Result.failure(Exception("Could not decrypt packet with any known keys"))
    }

    override suspend fun joinRoom(newCode: String) {
        roomCodeManager.joinRoom(newCode)
    }

    override fun getCurrentKey(): ByteArray? {
        val targetCode = roomCodeManager.joinedRooms.value.firstOrNull()
        return if (targetCode != null) roomCodeManager.getDerivedMeshKey(targetCode) else null
    }
}
