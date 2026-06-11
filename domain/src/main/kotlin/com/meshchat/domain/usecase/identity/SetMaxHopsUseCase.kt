package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.IdentityRepository

class SetMaxHopsUseCase(
    private val identityRepo: IdentityRepository
) {
    suspend operator fun invoke(hops: Int): Result<Unit> {
        return try {
            identityRepo.setMaxHops(hops)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}