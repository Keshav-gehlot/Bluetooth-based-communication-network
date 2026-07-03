package com.meshchat.ui.features.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.core.AvatarGenerator
import com.meshchat.domain.model.UserIdentity
import com.meshchat.domain.usecase.identity.GetIdentityUseCase
import com.meshchat.domain.usecase.identity.ObserveJoinedRoomsUseCase
import com.meshchat.domain.usecase.peers.ObservePeersUseCase
import com.meshchat.ui.core.ScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PeerUiModel(
    val nodeId: String,
    val displayName: String,
    val avatarColor: Long,
    val isOnline: Boolean,
    val hopDistance: Int,
    val lastSeen: Long
)

data class NearbyUiState(
    val peers: List<PeerUiModel>,
    val ownNodeId: String,
    val ownDisplayName: String,
    val ownAvatarColor: Long,
    val ownJoinedRooms: Set<String>
)

@HiltViewModel
class NearbyViewModel @Inject constructor(
    observePeersUseCase: ObservePeersUseCase,
    getIdentityUseCase: GetIdentityUseCase,
    observeJoinedRoomsUseCase: ObserveJoinedRoomsUseCase
) : ViewModel() {

    val state: StateFlow<ScreenUiState<NearbyUiState>> = combine(
        observePeersUseCase(),
        getIdentityUseCase(),
        observeJoinedRoomsUseCase()
    ) { peersList, ownIdentity, joinedRooms ->
        buildNearbyState(peersList, ownIdentity, joinedRooms)
    }
    .catch { e ->
        emit(ScreenUiState.Error(e.message ?: "Failed to load nearby peers"))
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScreenUiState.Loading
    )

    private fun buildNearbyState(
        peersList: List<com.meshchat.domain.model.Peer>,
        ownIdentity: UserIdentity,
        joinedRooms: Set<String>
    ): ScreenUiState<NearbyUiState> {
        val mappedPeers = peersList.map { peer ->
            val avatarData = AvatarGenerator.generate(peer.nodeId, peer.displayName)
            PeerUiModel(
                nodeId = peer.nodeId,
                displayName = peer.displayName,
                avatarColor = avatarData.backgroundColor,
                isOnline = peer.isOnline,
                hopDistance = peer.hopDistance,
                lastSeen = peer.lastSeen
            )
        }
        
        val ownAvatarData = AvatarGenerator.generate(ownIdentity.username, ownIdentity.username)
        return ScreenUiState.Success(
            NearbyUiState(
                peers = mappedPeers,
                ownNodeId = ownIdentity.username,
                ownDisplayName = ownIdentity.username,
                ownAvatarColor = ownAvatarData.backgroundColor,
                ownJoinedRooms = joinedRooms
            )
        )
    }
}
