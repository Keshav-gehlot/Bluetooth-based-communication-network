package com.meshchat.domain.repository

import com.meshchat.domain.model.Conversation
import com.meshchat.domain.model.EncryptedPacket
import com.meshchat.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun sendMessage(payload: EncryptedPacket): Result<Unit>
    suspend fun sendMessage(dst: String, payload: EncryptedPacket): Result<Unit>
    suspend fun sendBroadcast(payload: EncryptedPacket): Result<Unit>
    fun observeConversation(id: String): Flow<List<Message>>
    fun observeAllConversations(): Flow<List<Conversation>>
    fun observeBroadcasts(): Flow<List<Message>>
    suspend fun markConversationRead(conversationId: String)
    suspend fun clearAllMessages()
}
