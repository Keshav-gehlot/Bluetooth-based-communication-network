package com.meshchat.domain.repository

import com.meshchat.domain.model.Peer
import kotlinx.coroutines.flow.Flow

interface PeerRepository {
    fun observePeers(): Flow<List<Peer>>
    val peersFlow: Flow<List<Peer>>
}
