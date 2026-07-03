package com.meshchat.core

interface UsernameClaimBridge {
    suspend fun markUsernameClaimed()
    fun getActiveNodeId(mode: TransportMode): String
}
