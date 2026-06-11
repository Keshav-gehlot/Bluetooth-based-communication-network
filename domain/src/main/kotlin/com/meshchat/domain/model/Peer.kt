package com.meshchat.domain.model

data class Peer(
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    val isOnline: Boolean,
    val hopDistance: Int,
    val lastSeen: Long
)
