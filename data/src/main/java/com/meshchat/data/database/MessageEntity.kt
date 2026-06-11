package com.meshchat.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meshchat.domain.model.Message
import com.meshchat.domain.model.MessageStatus

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val text: String,
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: String,
    val hopCount: Int,
    val conversationId: String
) {
    fun toDomain(): Message {
        return Message(
            id = id,
            text = text,
            senderId = senderId,
            senderName = senderName,
            timestamp = timestamp,
            isOutgoing = isOutgoing,
            status = MessageStatus.valueOf(status),
            hopCount = hopCount,
            conversationId = conversationId
        )
    }

    companion object {
        fun fromDomain(domain: Message): MessageEntity {
            return MessageEntity(
                id = domain.id,
                text = domain.text,
                senderId = domain.senderId,
                senderName = domain.senderName,
                timestamp = domain.timestamp,
                isOutgoing = domain.isOutgoing,
                status = domain.status.name,
                hopCount = domain.hopCount,
                conversationId = domain.conversationId
            )
        }
    }
}
