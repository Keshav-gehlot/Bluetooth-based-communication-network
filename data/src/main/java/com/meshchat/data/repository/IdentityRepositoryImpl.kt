package com.meshchat.data.repository

import com.meshchat.core.IdentityManager
import com.meshchat.core.TransportMode
import com.meshchat.data.security.RoomCodeManager
import com.meshchat.domain.model.NodeIdentity
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

    // ── Identity observation ───────────────────────────────────────────────────
    override fun observeIdentity(): Flow<NodeIdentity> {
        return identityManager.getIdentity().map { core ->
            NodeIdentity(
                username = core.username,
                btNodeId = core.btNodeId,
                wifiNodeId = core.wifiNodeId,
                avatarSeed = core.avatarSeed,
                usernameClaimed = core.usernameClaimed,
                createdAt = core.createdAt
            )
        }
    }

    // ── Setup / claim lifecycle ────────────────────────────────────────────────
    override suspend fun createIdentity(username: String): NodeIdentity {
        val core = identityManager.createIdentity(username)
        return NodeIdentity(
            username = core.username,
            btNodeId = core.btNodeId,
            wifiNodeId = core.wifiNodeId,
            avatarSeed = core.avatarSeed,
            usernameClaimed = core.usernameClaimed,
            createdAt = core.createdAt
        )
    }

    override suspend fun markUsernameClaimed() {
        identityManager.markUsernameClaimed()
    }

    override suspend fun saveIdentity(identity: NodeIdentity) {
        identityManager.updateDisplayName(identity.username)
    }

    // ── Transport-aware ID resolution ──────────────────────────────────────────
    override fun getActiveNodeId(mode: TransportMode): String {
        return identityManager.getActiveNodeId(mode)
    }

    // ── Room / mesh settings (legacy retained) ─────────────────────────────────
    override fun observeJoinedRooms(): Flow<Set<String>> {
        return roomCodeManager.joinedRooms
    }

    override suspend fun updateDisplayName(name: String) {
        identityManager.updateDisplayName(name)
    }

    override fun isSetupCompleted(): Boolean {
        return identityManager.isSetupCompleted()
    }

    override fun setSetupCompleted(completed: Boolean) {
        runBlocking { identityManager.setSetupCompleted(completed) }
    }

    override suspend fun resetIdentity() {
        identityManager.resetIdentity()
    }

    override fun getMaxHops(): Flow<Int> = identityManager.getMaxHops()

    override fun getIdsEnabled(): Flow<Boolean> = identityManager.getIdsEnabled()

    override suspend fun setMaxHops(hops: Int) {
        identityManager.setMaxHops(hops)
    }

    override suspend fun setIdsEnabled(enabled: Boolean) {
        identityManager.setIdsEnabled(enabled)
    }

    override fun getTransportMode(): Flow<TransportMode> = identityManager.getTransportMode()

    override suspend fun saveTransportMode(mode: TransportMode) {
        identityManager.saveTransportMode(mode)
    }
}

