package com.meshchat.ui.features.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.domain.model.Message
import com.meshchat.domain.usecase.messaging.ObserveConversationUseCase
import com.meshchat.domain.usecase.messaging.SendMessageUseCase
import com.meshchat.domain.usecase.peers.ObservePeersUseCase
import com.meshchat.ui.core.ScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

data class ChatUiState(
    val messages: List<Message>,
    val isTyping: Boolean,
    val peerOnline: Boolean,
    val peerHops: Int
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeConversationUseCase: ObserveConversationUseCase,
    observePeersUseCase: ObservePeersUseCase,
    private val sendMessageUseCase: SendMessageUseCase
) : ViewModel() {

    // Type-safe nav serializes fields into SavedStateHandle automatically.
    // The key format is the field name from the @Serializable route class.
    val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""
    val peerId: String = savedStateHandle.get<String>("peerId") ?: ""
    val peerName: String = savedStateHandle.get<String>("peerName") ?: "Unknown"

    // Error events channel
    private val _sendError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sendError = _sendError.asSharedFlow()

    @OptIn(SavedStateHandleSaveableApi::class)
    var draftText by savedStateHandle.saveable { mutableStateOf("") }

    val state: StateFlow<ScreenUiState<ChatUiState>> = combine(
        observeConversationUseCase(conversationId),
        observePeersUseCase()
    ) { messages, peers ->
        buildChatState(messages, peers)
    }.catch { e ->
        emit(ScreenUiState.Error(e.message ?: "Error loading conversation"))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScreenUiState.Loading
    )

    private fun buildChatState(
        messages: List<Message>,
        peers: List<com.meshchat.domain.model.Peer>
    ): ScreenUiState<ChatUiState> {
        val peer = peers.firstOrNull { it.nodeId == peerId }
        val typing = false
        val online = peer?.isOnline == true
        val hops = peer?.hopDistance ?: 0
        return ScreenUiState.Success(ChatUiState(messages, typing, online, hops))
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            val result = sendMessageUseCase(text, peerId)
            result.onFailure { error ->
                _sendError.tryEmit(error.message ?: "Failed to send message")
            }
        }
    }
}
