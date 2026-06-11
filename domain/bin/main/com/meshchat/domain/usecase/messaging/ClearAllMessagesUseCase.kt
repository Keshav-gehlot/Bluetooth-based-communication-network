package com.meshchat.domain.usecase.messaging

import com.meshchat.domain.repository.ChatRepository

class ClearAllMessagesUseCase(
    private val chatRepo: ChatRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        return try {
            chatRepo.clearAllMessages()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}