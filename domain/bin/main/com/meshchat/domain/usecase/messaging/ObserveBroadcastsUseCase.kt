package com.meshchat.domain.usecase.messaging

import com.meshchat.domain.model.Message
import com.meshchat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class ObserveBroadcastsUseCase(
    private val chatRepo: ChatRepository
) {
    operator fun invoke(): Flow<List<Message>> {
        return chatRepo.observeBroadcasts()
    }
}
