package com.meshchat.core

object ChunkSizes {
    const val BLUETOOTH_BYTES = 512
    const val WIFI_BYTES = 65536
    const val BOTH_BYTES = 65536

    fun forMode(mode: TransportMode) = when (mode) {
        TransportMode.BLUETOOTH -> BLUETOOTH_BYTES
        TransportMode.WIFI -> WIFI_BYTES
        TransportMode.BOTH -> BOTH_BYTES
    }
}

object MediaLimits {
    const val IMAGE_MAX_BYTES_BT = 5 * 1024 * 1024
    const val IMAGE_MAX_BYTES_WIFI = 15 * 1024 * 1024
    const val VIDEO_MAX_BYTES = 30 * 1024 * 1024
    const val VIDEO_MAX_SECONDS = 30
    const val THUMBNAIL_MAX_BYTES = 8 * 1024
    const val IMAGE_MAX_DIMENSION = 1920

    fun isVideoAllowed(mode: TransportMode) =
        mode != TransportMode.BLUETOOTH
}
