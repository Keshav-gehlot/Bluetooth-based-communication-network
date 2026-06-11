package com.meshchat.core

import kotlin.math.abs

object AvatarGenerator {
    private val ACCENT_COLORS = listOf(
        0xFF00E87A, // Green
        0xFF00D4FF, // Blue
        0xFFFFAA00, // Amber
        0xFFFF3355, // Red/Pink
        0xFFB000FF, // Purple
        0xFFFF00D4, // Magenta
        0xFFFF5722, // Deep Orange
        0xFF9C27B0  // Violet
    )

    data class AvatarData(
        val initials: String,
        val backgroundColor: Long
    )

    fun generate(nodeId: String, displayName: String): AvatarData {
        val initials = displayName.trim().take(2).uppercase()
        val index = abs(nodeId.hashCode()) % ACCENT_COLORS.size
        return AvatarData(initials, ACCENT_COLORS[index])
    }
}
