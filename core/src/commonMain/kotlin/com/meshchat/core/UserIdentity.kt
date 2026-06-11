package com.meshchat.core

import kotlinx.serialization.Serializable

@Serializable
data class UserIdentity(
    val nodeId: String,      // auto-generated UUID, never changes
    val displayName: String, // user-chosen name
    val avatarSeed: String,  // used to generate deterministic avatar color/initials
    val publicKey: String,   // base64 encoded public key for future use
    val createdAt: Long
)
