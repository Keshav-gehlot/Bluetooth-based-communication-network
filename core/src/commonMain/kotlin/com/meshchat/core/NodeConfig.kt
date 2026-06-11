package com.meshchat.core

data class NodeConfig(
    val maxHops: Int = 3,
    val replayWindowMs: Long = 300_000, // 5 minutes
    val maxPacketsPerSecond: Int = 10,
    val idsEnabled: Boolean = true
)
