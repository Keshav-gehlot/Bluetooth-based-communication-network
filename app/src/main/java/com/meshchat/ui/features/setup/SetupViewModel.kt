package com.meshchat.ui.features.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.core.NodeIdentity
import com.meshchat.core.TransportMode
import com.meshchat.core.UsernameClaimProtocol
import com.meshchat.domain.repository.IdentityRepository
import com.meshchat.ui.PermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UI state ───────────────────────────────────────────────────────────────────

sealed class SetupUiState {
    object Idle      : SetupUiState()
    object Checking  : SetupUiState()   // "Checking mesh..."
    object Claimed   : SetupUiState()   // "Name is yours!"
    data class Conflict(val username: String) : SetupUiState()  // "Already taken"
    object NoMesh    : SetupUiState()   // offline — claim locally, re-assert on join
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val claimProtocol: UsernameClaimProtocol,
    val permissionManager: PermissionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /** The generated node IDs shown live in Step 2 — populated on ViewModel init. */
    private val _previewIdentity = MutableStateFlow<NodeIdentity?>(null)
    val previewIdentity: StateFlow<NodeIdentity?> = _previewIdentity.asStateFlow()

    // Compose-state fields (two-way binding friendly)
    var username by mutableStateOf("")
        private set
    var selectedMode by mutableStateOf(TransportMode.BLUETOOTH)
        private set

    init {
        // Load the already-generated permanent IDs so Step 2 can display them
        viewModelScope.launch {
            _previewIdentity.value = identityRepository.observeIdentity().first().toCore()
        }
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun onUsernameChanged(value: String) {
        // Allowed chars: letters, digits, underscore, hyphen. Max 24 chars.
        username = value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24)
        _uiState.value = SetupUiState.Idle   // reset on any edit
    }

    fun onModeSelected(mode: TransportMode) {
        selectedMode = mode
    }

    fun onConfirm() {
        if (username.isBlank()) return
        if (!UsernameClaimProtocol.isValidUsername(username)) return

        viewModelScope.launch {
            _uiState.value = SetupUiState.Checking

            val identity = identityRepository.createIdentity(username)

            val result = claimProtocol.claimUsername(username, identity.toCore())

            _uiState.value = when (result) {
                is UsernameClaimProtocol.ClaimResult.Claimed  -> {
                    identityRepository.setSetupCompleted(true)
                    identityRepository.saveTransportMode(selectedMode)
                    SetupUiState.Claimed
                }
                is UsernameClaimProtocol.ClaimResult.Conflict ->
                    SetupUiState.Conflict(username)
                is UsernameClaimProtocol.ClaimResult.Timeout  -> {
                    // No mesh found — allow anyway, re-assert on next join
                    identityRepository.setSetupCompleted(true)
                    identityRepository.saveTransportMode(selectedMode)
                    SetupUiState.NoMesh
                }
            }
        }
    }

    fun onRetryUsername() {
        _uiState.value = SetupUiState.Idle
    }

    // ── Validation helpers ─────────────────────────────────────────────────────

    val isUsernameValid: Boolean
        get() = UsernameClaimProtocol.isValidUsername(username)

    val usernameValidationMessage: String
        get() = when {
            username.isEmpty()  -> ""
            !isUsernameValid    -> "Letters, digits, _ and - only. Max 24 characters."
            else                -> ""
        }
}

private fun com.meshchat.domain.model.NodeIdentity.toCore(): com.meshchat.core.NodeIdentity {
    return com.meshchat.core.NodeIdentity(
        username = username,
        btNodeId = btNodeId,
        wifiNodeId = wifiNodeId,
        avatarSeed = avatarSeed,
        createdAt = createdAt,
        usernameClaimed = usernameClaimed
    )
}

