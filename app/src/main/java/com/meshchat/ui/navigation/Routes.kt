package com.meshchat.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object Splash

@Serializable
object Setup

@Serializable
object Chats

@Serializable
data class Chat(val conversationId: String, val peerId: String, val peerName: String)

@Serializable
object Broadcast

@Serializable
object Network

@Serializable
object Settings
