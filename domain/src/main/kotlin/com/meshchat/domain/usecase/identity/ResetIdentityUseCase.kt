package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.ChatRepository
import com.meshchat.domain.repository.IdentityRepository

class ResetIdentityUseCase(
    private val chatRepo: ChatRepository,
    private val identityRepo: IdentityRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        return try {
            chatRepo.clearAllMessages()
            identityRepo.resetIdentity()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}