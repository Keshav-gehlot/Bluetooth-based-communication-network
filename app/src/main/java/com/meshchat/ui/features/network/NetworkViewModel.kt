package com.meshchat.ui.features.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.domain.model.UserIdentity
import com.meshchat.domain.model.Peer
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

data class NetworkUiState(
    val peers: List<Peer>,
    val identity: UserIdentity,
    val joinedRooms: Set<String>
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    observePeersUseCase: ObservePeersUseCase,
    getIdentityUseCase: GetIdentityUseCase,
    observeJoinedRoomsUseCase: ObserveJoinedRoomsUseCase
) : ViewModel() {

    val state: StateFlow<ScreenUiState<NetworkUiState>> = combine(
        observePeersUseCase(),
        getIdentityUseCase(),
        observeJoinedRoomsUseCase()
    ) { peers, identity, joinedRooms ->
        buildNetworkState(peers, identity, joinedRooms)
    }.catch { e ->
        emit(ScreenUiState.Error(e.message ?: "Failed to load network state"))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScreenUiState.Loading
    )

    private fun buildNetworkState(
        peers: List<Peer>,
        identity: UserIdentity,
        joinedRooms: Set<String>
    ): ScreenUiState<NetworkUiState> {
        return ScreenUiState.Success(NetworkUiState(peers, identity, joinedRooms))
    }
}
