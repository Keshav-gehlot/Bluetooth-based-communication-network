package com.meshchat.data.repository

import com.meshchat.core.IdentityManager
import com.meshchat.data.security.RoomCodeManager
import com.meshchat.domain.model.UserIdentity
import com.meshchat.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val identityManager: IdentityManager,
    private val roomCodeManager: RoomCodeManager
) : IdentityRepository {

    override fun observeIdentity(): Flow<UserIdentity> {
        return identityManager.getIdentity().map { identity ->
            UserIdentity(
                nodeId = identity.nodeId,
                displayName = identity.displayName,
                avatarSeed = identity.avatarSeed
            )
        }
    }

    override fun observeJoinedRooms(): Flow<Set<String>> {
        return roomCodeManager.joinedRooms
    }

    override suspend fun saveIdentity(identity: UserIdentity) {
        identityManager.updateDisplayName(identity.displayName)
    }

    override suspend fun updateDisplayName(name: String) {
        identityManager.updateDisplayName(name)
    }

    override fun isSetupCompleted(): Boolean {
        return identityManager.isSetupCompleted()
    }

    override fun setSetupCompleted(completed: Boolean) {
        runBlocking {
            identityManager.setSetupCompleted(completed)
        }
    }

    override suspend fun resetIdentity() {
        identityManager.resetIdentity()
    }

    override fun getMaxHops(): Flow<Int> {
        return identityManager.getMaxHops()
    }

    override fun getIdsEnabled(): Flow<Boolean> {
        return identityManager.getIdsEnabled()
    }

    override suspend fun setMaxHops(hops: Int) {
        identityManager.setMaxHops(hops)
    }

    override suspend fun setIdsEnabled(enabled: Boolean) {
        identityManager.setIdsEnabled(enabled)
    }
}
