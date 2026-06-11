package com.meshchat.domain.usecase.identity

import com.meshchat.domain.model.UserIdentity
import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow

class GetIdentityUseCase(
    private val identityRepo: IdentityRepository
) {
    operator fun invoke(): Flow<UserIdentity> {
        return identityRepo.observeIdentity()
    }
}
