package com.meshchat.ui.core

sealed class ScreenUiState<out T> {
    object Loading : ScreenUiState<Nothing>()
    data class Success<T>(val data: T) : ScreenUiState<T>()
    data class Error(val message: String) : ScreenUiState<Nothing>()
    object Empty : ScreenUiState<Nothing>()
}
