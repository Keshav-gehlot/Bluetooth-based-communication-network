package com.meshchat.domain.usecase.identity

import com.meshchat.domain.exception.ValidationException
import com.meshchat.domain.repository.CryptoRepository
class JoinRoomUseCase(
    private val cryptoRepository: CryptoRepository
) {
    suspend operator fun invoke(newCode: String): Result<Unit> {
        if (newCode.isBlank()) {
            return Result.failure(ValidationException("Room code cannot be blank"))
        }
        if (newCode.length != 5 || !newCode.matches(Regex("^[a-zA-Z0-9]+\$"))) {
            return Result.failure(ValidationException("Room code must be exactly 5 alphanumeric characters"))
        }

        return try {
            cryptoRepository.joinRoom(newCode.uppercase())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
