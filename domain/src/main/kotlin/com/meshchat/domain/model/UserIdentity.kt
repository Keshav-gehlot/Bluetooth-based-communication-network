package com.meshchat.domain.model

/**
 * Domain representation of a node's permanent dual-transport identity.
 * Mirrors [com.meshchat.core.NodeIdentity].
 */
data class NodeIdentity(
    val username: String,
    val btNodeId: String,
    val wifiNodeId: String,
    val avatarSeed: String,
    val usernameClaimed: Boolean,
    val createdAt: Long = 0L
)

/**
 * Legacy alias retained for backward compatibility during migration.
 * Prefer [NodeIdentity] in all new code.
 */
@Deprecated("Use NodeIdentity instead", ReplaceWith("NodeIdentity"))
typealias UserIdentity = NodeIdentity