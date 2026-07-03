package com.meshchat.domain.repository

import com.meshchat.core.TransportMode
import com.meshchat.domain.model.NodeIdentity
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    // Identity observation
    fun observeIdentity(): Flow<NodeIdentity>

    // Setup / claim lifecycle
    suspend fun createIdentity(username: String): NodeIdentity
    suspend fun markUsernameClaimed()
    suspend fun saveIdentity(identity: NodeIdentity)

    // Transport-aware ID resolution
    fun getActiveNodeId(mode: TransportMode): String

    // Room / mesh settings (retained from legacy)
    fun observeJoinedRooms(): Flow<Set<String>>
    suspend fun updateDisplayName(name: String)
    fun isSetupCompleted(): Boolean
    fun setSetupCompleted(completed: Boolean)
    suspend fun resetIdentity()
    fun getMaxHops(): Flow<Int>
    fun getIdsEnabled(): Flow<Boolean>
    suspend fun setMaxHops(hops: Int)
    suspend fun setIdsEnabled(enabled: Boolean)
    fun getTransportMode(): Flow<TransportMode>
    suspend fun saveTransportMode(mode: TransportMode)
}

