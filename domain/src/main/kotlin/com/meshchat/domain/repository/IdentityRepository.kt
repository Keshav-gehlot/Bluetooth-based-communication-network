package com.meshchat.domain.repository

import com.meshchat.domain.model.UserIdentity
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun observeIdentity(): Flow<UserIdentity>
    fun observeJoinedRooms(): Flow<Set<String>>
    suspend fun saveIdentity(identity: UserIdentity)
    suspend fun updateDisplayName(name: String)
    fun isSetupCompleted(): Boolean
    fun setSetupCompleted(completed: Boolean)
    suspend fun resetIdentity()
    fun getMaxHops(): Flow<Int>
    fun getIdsEnabled(): Flow<Boolean>
    suspend fun setMaxHops(hops: Int)
    suspend fun setIdsEnabled(enabled: Boolean)
}
