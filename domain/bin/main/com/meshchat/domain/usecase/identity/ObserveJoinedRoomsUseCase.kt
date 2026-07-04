package com.meshchat.domain.usecase.identity

import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
class ObserveJoinedRoomsUseCase(
    private val identityRepository: IdentityRepository
) {
    operator fun invoke(): Flow<Set<String>> {
        return identityRepository.observeJoinedRooms()
    }
}