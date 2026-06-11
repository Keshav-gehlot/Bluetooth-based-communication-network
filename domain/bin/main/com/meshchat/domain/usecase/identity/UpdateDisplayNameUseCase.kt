package com.meshchat.domain.usecase.identity

import com.meshchat.domain.exception.ValidationException
import com.meshchat.domain.repository.IdentityRepository

class UpdateDisplayNameUseCase(
    private val identityRepo: IdentityRepository
) {
    suspend operator fun invoke(name: String): Result<Unit> {
        if (name.isBlank()) {
            return Result.failure(ValidationException("Display name cannot be blank"))
        }
        if (name.length !in 1..24) {
            return Result.failure(ValidationException("Display name must be between 1 and 24 characters"))
        }
        if (!name.matches(Regex("^[a-zA-Z0-9 ]+\$"))) {
            return Result.failure(ValidationException("Display name cannot contain special characters"))
        }

        return try {
            identityRepo.updateDisplayName(name)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
