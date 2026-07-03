package com.meshchat.domain.model

data class VoiceSession(
    val sessionId: String,
    val senderUsername: String,
    val conversationId: String,
    val state: VoiceSessionState,
    val durationMs: Long,
    val timestamp: Long,
    val isOutgoing: Boolean,
)

sealed class VoiceSessionState {
    object Transmitting : VoiceSessionState()  // PTT held
    object Receiving    : VoiceSessionState()  // peer transmitting
    data class Completed(val durationMs: Long) : VoiceSessionState()
    object Busy         : VoiceSessionState()  // channel occupied
}
