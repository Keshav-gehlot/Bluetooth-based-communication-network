package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow

class ObserveIdsEnabledUseCase(
    private val identityRepo: IdentityRepository
) {
    operator fun invoke(): Flow<Boolean> {
        return identityRepo.getIdsEnabled()
    }
}