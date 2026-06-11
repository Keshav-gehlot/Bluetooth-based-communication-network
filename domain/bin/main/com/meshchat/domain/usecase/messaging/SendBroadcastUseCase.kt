package com.meshchat.domain.usecase.messaging

import com.meshchat.domain.exception.ValidationException
import com.meshchat.domain.repository.ChatRepository
import com.meshchat.domain.repository.CryptoRepository

class SendBroadcastUseCase(
    private val chatRepo: ChatRepository,
    private val cryptoRepo: CryptoRepository
) {
    suspend operator fun invoke(text: String, roomId: String? = null): Result<Unit> {
        if (text.isBlank()) {
            return Result.failure(ValidationException("Broadcast text cannot be blank"))
        }
        if (text.length > 2000) {
            return Result.failure(ValidationException("Broadcast text cannot exceed 2000 characters"))
        }

        val encryptedPacket = cryptoRepo.encryptMessage(text, roomId)
        return chatRepo.sendBroadcast(encryptedPacket)
    }
}
