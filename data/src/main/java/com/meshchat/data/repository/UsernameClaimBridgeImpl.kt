package com.meshchat.data.repository

import com.meshchat.core.TransportMode
import com.meshchat.core.UsernameClaimBridge
import com.meshchat.domain.repository.IdentityRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsernameClaimBridgeImpl @Inject constructor(
    private val identityRepository: IdentityRepository
) : UsernameClaimBridge {
    override suspend fun markUsernameClaimed() {
        identityRepository.markUsernameClaimed()
    }

    override fun getActiveNodeId(mode: TransportMode): String {
        return identityRepository.getActiveNodeId(mode)
    }
}
