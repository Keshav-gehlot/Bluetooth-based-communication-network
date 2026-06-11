package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.IdentityRepository

class SetSetupCompletedUseCase(
    private val identityRepo: IdentityRepository
) {
    suspend operator fun invoke(completed: Boolean): Result<Unit> {
        return try {
            identityRepo.setSetupCompleted(completed)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}