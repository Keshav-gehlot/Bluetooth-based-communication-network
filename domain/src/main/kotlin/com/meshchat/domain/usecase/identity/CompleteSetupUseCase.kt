package com.meshchat.domain.usecase.identity

import com.meshchat.domain.exception.ValidationException

class CompleteSetupUseCase(
    private val updateDisplayNameUseCase: UpdateDisplayNameUseCase,
    private val joinRoomUseCase: JoinRoomUseCase,
    private val setSetupCompletedUseCase: SetSetupCompletedUseCase
) {
    suspend operator fun invoke(displayName: String, roomCode: String): Result<Unit> {
        if (displayName.isBlank()) {
            return Result.failure(ValidationException("Name cannot be empty"))
        }

        val displayNameResult = updateDisplayNameUseCase(displayName)
        if (displayNameResult.isFailure) {
            return displayNameResult
        }

        val roomCodeResult = joinRoomUseCase(roomCode)
        if (roomCodeResult.isFailure) {
            return roomCodeResult
        }

        return setSetupCompletedUseCase(true)
    }
}