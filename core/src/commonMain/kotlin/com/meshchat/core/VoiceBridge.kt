package com.meshchat.core

interface VoiceBridge {
    suspend fun onVoiceStart(payload: VoiceStartPayload)
    suspend fun onVoiceFrame(payload: VoiceFramePayload)
    suspend fun onVoiceEnd(payload: VoiceEndPayload, conversationId: String)
}
