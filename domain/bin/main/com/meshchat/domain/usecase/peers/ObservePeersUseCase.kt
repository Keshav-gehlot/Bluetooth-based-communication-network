package com.meshchat.domain.usecase.peers

import com.meshchat.domain.model.Peer
import com.meshchat.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow

class ObservePeersUseCase(
    private val peerRepo: PeerRepository
) {
    operator fun invoke(): Flow<List<Peer>> {
        return peerRepo.observePeers()
    }
}
