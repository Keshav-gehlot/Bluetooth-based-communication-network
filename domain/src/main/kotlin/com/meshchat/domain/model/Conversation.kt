package com.meshchat.domain.model

data class Conversation(
    val id: String,
    val peerId: String,
    val peerName: String,
    val lastMessage: String,
    val lastMessageTime: Long,
    val unreadCount: Int
)
