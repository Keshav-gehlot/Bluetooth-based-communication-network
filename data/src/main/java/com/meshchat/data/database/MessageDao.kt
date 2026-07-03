package com.meshchat.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(msg: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :id AND mediaTransferId IS NOT NULL ORDER BY timestamp ASC")
    fun observeMediaForConversation(id: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET mediaStatus = :status, mediaLocalPath = CASE WHEN :localPath IS NOT NULL THEN :localPath ELSE mediaLocalPath END WHERE mediaTransferId = :transferId")
    suspend fun updateMediaStatus(transferId: String, status: String, localPath: String?)
}
