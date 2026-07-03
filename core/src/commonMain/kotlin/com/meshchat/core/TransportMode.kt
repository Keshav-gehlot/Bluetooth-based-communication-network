package com.meshchat.core

/**
 * Which physical radio(s) the mesh should use.
 *
 * - [BLUETOOTH] — Nearby P2P_STAR, lower power, shorter range.
 * - [WIFI]      — Nearby P2P_CLUSTER, higher speed, longer range.
 * - [BOTH]      — Both transports active simultaneously, peer sets merged.
 */
enum class TransportMode {
    BLUETOOTH,
    WIFI,
    BOTH
}
