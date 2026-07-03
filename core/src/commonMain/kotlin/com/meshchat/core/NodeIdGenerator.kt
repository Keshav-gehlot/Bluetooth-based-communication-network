package com.meshchat.core

import java.util.UUID

/**
 * Generates permanent, human-readable node IDs for each transport.
 *
 * IDs are generated ONCE on first launch and stored permanently.
 * Format: "BT-XXXX-XXXX" / "WIFI-XXXX-XXXX" where X is uppercase hex.
 */
object NodeIdGenerator {

    fun generateBtId(): String {
        val suffix = UUID.randomUUID().toString()
            .replace("-", "").uppercase().take(8)
        return "BT-${suffix.take(4)}-${suffix.takeLast(4)}"
    }

    fun generateWifiId(): String {
        val suffix = UUID.randomUUID().toString()
            .replace("-", "").uppercase().take(8)
        return "WIFI-${suffix.take(4)}-${suffix.takeLast(4)}"
    }
}
