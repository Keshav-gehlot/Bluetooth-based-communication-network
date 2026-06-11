package com.meshchat.ui.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.domain.model.Conversation
import com.meshchat.domain.model.Peer
import com.meshchat.domain.usecase.messaging.ObserveConversationsUseCase
import com.meshchat.domain.usecase.peers.ObservePeersUseCase
import com.meshchat.ui.core.ScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ChatsUiState(
    val conversations: List<Conversation>,
    val onlinePeers: List<Peer>
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    observeConversationsUseCase: ObserveConversationsUseCase,
    observePeersUseCase: ObservePeersUseCase
) : ViewModel() {

    val state: StateFlow<ScreenUiState<ChatsUiState>> = combine(
        observeConversationsUseCase(),
        observePeersUseCase()
    ) { conversations, peers ->
        if (peers.isEmpty()) {
            ScreenUiState.Empty
        } else {
            ScreenUiState.Success(ChatsUiState(conversations, peers))
        }
    }.catch { e ->
        emit(ScreenUiState.Error(e.message ?: "Unknown error occurred"))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScreenUiState.Loading
    )
}
