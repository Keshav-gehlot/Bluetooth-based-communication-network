package com.meshchat.ui.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.domain.model.UserIdentity
import com.meshchat.domain.usecase.identity.JoinRoomUseCase
import com.meshchat.domain.usecase.identity.GetIdentityUseCase
import com.meshchat.domain.usecase.identity.ObserveIdsEnabledUseCase
import com.meshchat.domain.usecase.identity.ObserveMaxHopsUseCase
import com.meshchat.domain.usecase.identity.ObserveJoinedRoomsUseCase
import com.meshchat.domain.usecase.identity.UpdateDisplayNameUseCase
import com.meshchat.domain.usecase.identity.SetIdsEnabledUseCase
import com.meshchat.domain.usecase.identity.SetMaxHopsUseCase
import com.meshchat.domain.usecase.identity.ResetIdentityUseCase
import com.meshchat.domain.usecase.messaging.ClearAllMessagesUseCase
import com.meshchat.ui.core.ScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val identity: UserIdentity,
    val joinedRooms: Set<String>,
    val maxHops: Int = 3,
    val idsEnabled: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    getIdentityUseCase: GetIdentityUseCase,
    private val updateDisplayNameUseCase: UpdateDisplayNameUseCase,
    private val joinRoomUseCase: JoinRoomUseCase,
    private val observeJoinedRoomsUseCase: ObserveJoinedRoomsUseCase,
    private val observeMaxHopsUseCase: ObserveMaxHopsUseCase,
    private val observeIdsEnabledUseCase: ObserveIdsEnabledUseCase,
    private val setMaxHopsUseCase: SetMaxHopsUseCase,
    private val setIdsEnabledUseCase: SetIdsEnabledUseCase,
    private val clearAllMessagesUseCase: ClearAllMessagesUseCase,
    private val resetIdentityUseCase: ResetIdentityUseCase
) : ViewModel() {

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    val state: StateFlow<ScreenUiState<SettingsUiState>> = combine(
        getIdentityUseCase(),
        observeJoinedRoomsUseCase(),
        observeMaxHopsUseCase(),
        observeIdsEnabledUseCase()
    ) { identity, joinedRooms, maxHops, idsEnabled ->
        buildSettingsState(identity, joinedRooms, maxHops, idsEnabled)
    }
        .catch { e -> emit(ScreenUiState.Error(e.message ?: "Failed to load settings")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ScreenUiState.Loading
        )

    private fun buildSettingsState(
        identity: UserIdentity,
        joinedRooms: Set<String>,
        maxHops: Int,
        idsEnabled: Boolean
    ): ScreenUiState<SettingsUiState> {
        return ScreenUiState.Success(SettingsUiState(identity, joinedRooms, maxHops, idsEnabled))
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            updateDisplayNameUseCase(name)
                .onFailure { error -> _events.tryEmit(error.message ?: "Failed to update display name") }
        }
    }

    fun joinRoom(newCode: String) {
        viewModelScope.launch {
            joinRoomUseCase(newCode)
                .onFailure { error -> _events.tryEmit(error.message ?: "Failed to join room") }
        }
    }

    fun toggleIds(enabled: Boolean) {
        viewModelScope.launch {
            setIdsEnabledUseCase(enabled)
        }
    }

    fun updateMaxHops(hops: Int) {
        viewModelScope.launch {
            setMaxHopsUseCase(hops)
        }
    }

    fun clearAllMessages() {
        viewModelScope.launch {
            clearAllMessagesUseCase()
        }
    }

    fun resetIdentity() {
        viewModelScope.launch {
            resetIdentityUseCase()
        }
    }
}
