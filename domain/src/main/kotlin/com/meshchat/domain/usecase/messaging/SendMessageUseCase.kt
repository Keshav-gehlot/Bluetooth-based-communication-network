package com.meshchat.domain.usecase.messaging

import com.meshchat.domain.exception.ValidationException
import com.meshchat.domain.model.ChatPayload
import com.meshchat.domain.repository.ChatRepository
import com.meshchat.domain.repository.CryptoRepository

class SendMessageUseCase(
    private val chatRepo: ChatRepository,
    private val cryptoRepo: CryptoRepository
) {
    suspend operator fun invoke(text: String, dst: String): Result<Unit> {
        if (text.isBlank()) {
            return Result.failure(ValidationException("Message text cannot be blank"))
        }
        if (text.length > 2000) {
            return Result.failure(ValidationException("Message text cannot exceed 2000 characters"))
        }

        val chatPayload = ChatPayload(dst = dst, text = text)
        val encryptedPacket = cryptoRepo.encryptMessage(chatPayload.encode())

        return chatRepo.sendMessage(encryptedPacket)
    }
}
