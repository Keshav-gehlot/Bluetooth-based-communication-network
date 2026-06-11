package com.meshchat.ui.features.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.domain.usecase.identity.CompleteSetupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val completeSetupUseCase: CompleteSetupUseCase
) : ViewModel() {

    private val _displayName = MutableStateFlow("")
    val displayName = _displayName.asStateFlow()

    private val _roomCode = MutableStateFlow(generateRandomRoomCode())
    val roomCode = _roomCode.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    fun updateDisplayName(name: String) {
        if (name.length <= 24) {
            _displayName.value = name
        }
    }

    fun updateRoomCode(code: String) {
        _roomCode.value = code.uppercase()
    }

    fun completeSetup(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val nameTrimmed = _displayName.value.trim()
        val codeTrimmed = _roomCode.value.trim()

        if (nameTrimmed.isEmpty()) {
            onError("Name cannot be empty")
            return
        }
        if (codeTrimmed.length != 5 || !codeTrimmed.matches(Regex("^[a-zA-Z0-9]+\$"))) {
            onError("Room code must be exactly 5 alphanumeric characters")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = completeSetupUseCase(nameTrimmed, codeTrimmed)
                if (result.isSuccess) {
                    onSuccess()
                } else {
                    onError(result.exceptionOrNull()?.message ?: "Failed to complete setup")
                }
            } catch (e: Exception) {
                onError(e.message ?: "An unexpected error occurred")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun generateRandomRoomCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..5)
            .map { allowedChars.random() }
            .joinToString("")
    }
}
