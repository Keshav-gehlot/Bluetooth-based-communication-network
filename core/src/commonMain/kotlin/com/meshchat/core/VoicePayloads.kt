package com.meshchat.core

import kotlinx.serialization.Serializable

@Serializable
data class VoiceStartPayload(
    val sessionId: String,       // UUID — unique per PTT press
    val senderUsername: String,
    val senderNodeId: String,
    val conversationId: String,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val opusBitrate: Int,        // 8000 for BT, 24000 for WiFi
    val timestamp: Long,
)

@Serializable
data class VoiceFramePayload(
    val sessionId: String,
    val seq: Int,                // monotonically increasing per session
    val timestampMs: Int,        // samples since session start
    val opusData: String,        // base64 Opus-encoded frame bytes
)

@Serializable
data class VoiceEndPayload(
    val sessionId: String,
    val finalSeq: Int,           // highest seq sent
    val durationMs: Long,
)
