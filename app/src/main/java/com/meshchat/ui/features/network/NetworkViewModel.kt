package com.meshchat.ui.features.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.core.TransportMode
import com.meshchat.domain.model.NodeIdentity
import com.meshchat.domain.model.Peer
import com.meshchat.domain.repository.IdentityRepository
import com.meshchat.domain.usecase.identity.GetIdentityUseCase
import com.meshchat.domain.usecase.identity.ObserveJoinedRoomsUseCase
import com.meshchat.domain.usecase.peers.ObservePeersUseCase
import com.meshchat.transports.DualTransportManager
import com.meshchat.ui.PermissionManager
import com.meshchat.ui.core.ScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkUiState(
    val peers: List<Peer>,
    val identity: NodeIdentity,
    val joinedRooms: Set<String>,
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    observePeersUseCase: ObservePeersUseCase,
    getIdentityUseCase: GetIdentityUseCase,
    observeJoinedRoomsUseCase: ObserveJoinedRoomsUseCase,
    private val dualTransportManager: DualTransportManager,
    private val identityRepository: IdentityRepository,
    val permissionManager: PermissionManager,
) : ViewModel() {

    // ── Network state ──────────────────────────────────────────────────────────
    val state: StateFlow<ScreenUiState<NetworkUiState>> = combine(
        observePeersUseCase(),
        getIdentityUseCase(),
        observeJoinedRoomsUseCase(),
    ) { peers, identity, joinedRooms ->
        ScreenUiState.Success(NetworkUiState(peers, identity, joinedRooms)) as ScreenUiState<NetworkUiState>
    }.catch { e ->
        emit(ScreenUiState.Error(e.message ?: "Failed to load network state"))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScreenUiState.Loading,
    )

    // ── Transport mode ─────────────────────────────────────────────────────────
    val transportMode: StateFlow<TransportMode> = dualTransportManager.modeFlow

    fun switchTransport(mode: TransportMode) {
        viewModelScope.launch {
            dualTransportManager.switchMode(mode)
        }
    }
}

