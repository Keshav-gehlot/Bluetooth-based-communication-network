package com.meshchat.voice

import com.meshchat.core.TransportMode

object VoiceBitratePolicy {
    const val SAMPLE_RATE        = 16_000   // 16kHz — wideband voice
    const val FRAME_SIZE_SAMPLES = 320     // 20ms at 16kHz
    const val FRAME_SIZE_BYTES   = 640      // 16-bit PCM per frame (320 samples * 2 bytes/sample)
    const val CHANNELS           = 1        // mono

    fun opusBitrate(mode: TransportMode): Int {
        return when (mode) {
            TransportMode.BLUETOOTH -> 8_000    // 8kbps — fits BLE MTU easily
            TransportMode.WIFI -> 24_000        // 24kbps — clear wideband
            TransportMode.BOTH -> 24_000
        }
    }

    const val JITTER_MIN_MS      = 60      // 3 frames minimum buffer
    const val JITTER_MAX_MS      = 120     // 6 frames maximum
    const val PACKET_TIMEOUT_MS  = 200     // drop if older than this
    const val MAX_HOP_VOICE      = 1       // voice never relays > 1 hop
}
