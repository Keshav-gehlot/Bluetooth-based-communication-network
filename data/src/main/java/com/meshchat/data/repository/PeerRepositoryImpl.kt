package com.meshchat.data.repository

import com.meshchat.core.PresenceManager
import com.meshchat.domain.model.Peer
import com.meshchat.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRepositoryImpl @Inject constructor(
    private val presenceManager: PresenceManager
) : PeerRepository {

    override fun observePeers(): Flow<List<Peer>> {
        return presenceManager.peers.map { peerMap ->
            peerMap.map { (username, info) ->
                Peer(
                    nodeId = username,
                    displayName = info.username,
                    avatarSeed = info.avatarSeed,
                    isOnline = info.isOnline,
                    hopDistance = info.hopDistance,
                    lastSeen = info.lastSeen
                )
            }
        }
    }

    override val peersFlow: Flow<List<Peer>> = observePeers()
}

