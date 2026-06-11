package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.IdentityRepository

class IsSetupCompletedUseCase(
    private val identityRepo: IdentityRepository
) {
    operator fun invoke(): Boolean {
        return identityRepo.isSetupCompleted()
    }
}