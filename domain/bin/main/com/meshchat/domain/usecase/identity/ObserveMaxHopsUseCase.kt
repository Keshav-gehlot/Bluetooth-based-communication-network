package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow

class ObserveMaxHopsUseCase(
    private val identityRepo: IdentityRepository
) {
    operator fun invoke(): Flow<Int> {
        return identityRepo.getMaxHops()
    }
}