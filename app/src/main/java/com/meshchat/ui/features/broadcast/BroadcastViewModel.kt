package com.meshchat.ui.features.broadcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.domain.usecase.identity.ObserveJoinedRoomsUseCase
import com.meshchat.domain.usecase.messaging.ObserveBroadcastsUseCase
import com.meshchat.domain.usecase.messaging.SendBroadcastUseCase
import com.meshchat.domain.usecase.peers.ObservePeersUseCase
import com.meshchat.ui.core.ScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BroadcastMessage(
    val msgId: String,
    val senderName: String,
    val senderAvatarColor: Long,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val receivedByCount: Int
)

data class BroadcastUiState(
    val messages: List<BroadcastMessage>,
    val onlinePeerCount: Int,
    val activeRoomId: String,
    val availableRooms: Set<String>
)

@HiltViewModel
class BroadcastViewModel @Inject constructor(
    private val observeBroadcastsUseCase: ObserveBroadcastsUseCase,
    private val observePeersUseCase: ObservePeersUseCase,
    private val observeJoinedRoomsUseCase: ObserveJoinedRoomsUseCase,
    private val sendBroadcastUseCase: SendBroadcastUseCase
) : ViewModel() {

    private val _sendError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sendError = _sendError.asSharedFlow()

    private val _activeRoomId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ScreenUiState<BroadcastUiState>> = combine(
        _activeRoomId.flatMapLatest { room ->
            observeBroadcastsUseCase()
        },
        observePeersUseCase(),
        observeJoinedRoomsUseCase()
    ) { messages, peers, joinedRooms ->
        val currentActiveRoom = _activeRoomId.value ?: joinedRooms.firstOrNull() ?: "MESH0"
        
        if (_activeRoomId.value == null && joinedRooms.isNotEmpty()) {
            _activeRoomId.value = joinedRooms.first()
        }

        val roomMessages = messages.filter { it.conversationId == "broadcast_$currentActiveRoom" }

        buildBroadcastState(roomMessages, peers, currentActiveRoom, joinedRooms)
    }
    .catch { e -> emit(ScreenUiState.Error(e.message ?: "Failed to load broadcasts")) }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScreenUiState.Loading
    )

    private fun buildBroadcastState(
        messages: List<com.meshchat.domain.model.Message>,
        peers: List<com.meshchat.domain.model.Peer>,
        activeRoomId: String,
        availableRooms: Set<String>
    ): ScreenUiState<BroadcastUiState> {
        val onlineCount = peers.count { it.isOnline }
        val broadcastMessages = messages.map { msg ->
            BroadcastMessage(
                msgId = msg.id,
                senderName = msg.senderName,
                senderAvatarColor = if (msg.isOutgoing) 0xFF00E87A else 0xFF00D4FF,
                text = msg.text,
                timestamp = msg.timestamp,
                isOutgoing = msg.isOutgoing,
                receivedByCount = if (msg.isOutgoing) onlineCount else 1
            )
        }
        return ScreenUiState.Success(BroadcastUiState(broadcastMessages, onlineCount, activeRoomId, availableRooms))
    }

    fun setActiveRoom(roomId: String) {
        _activeRoomId.value = roomId
    }

    fun sendBroadcast(text: String) {
        val currentRoom = _activeRoomId.value
        if (currentRoom == null) {
            _sendError.tryEmit("No active room selected")
            return
        }

        viewModelScope.launch {
            val result = sendBroadcastUseCase(text, currentRoom)
            result.onFailure { error ->
                _sendError.tryEmit(error.message ?: "Failed to send broadcast")
            }
        }
    }
}
