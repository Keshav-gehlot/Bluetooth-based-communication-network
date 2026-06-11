package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.IdentityRepository

class SetIdsEnabledUseCase(
    private val identityRepo: IdentityRepository
) {
    suspend operator fun invoke(enabled: Boolean): Result<Unit> {
        return try {
            identityRepo.setIdsEnabled(enabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}