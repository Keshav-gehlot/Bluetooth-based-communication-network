package com.meshchat.domain.usecase.messaging

import com.meshchat.domain.model.Conversation
import com.meshchat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

class ObserveConversationsUseCase(
    private val chatRepo: ChatRepository
) {
    operator fun invoke(): Flow<List<Conversation>> {
        return chatRepo.observeAllConversations()
    }
}
