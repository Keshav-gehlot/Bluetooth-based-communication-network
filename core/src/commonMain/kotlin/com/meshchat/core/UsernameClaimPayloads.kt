package com.meshchat.core

import kotlinx.serialization.Serializable

/**
 * Sent by a node broadcasting its desire to own a username on the mesh.
 * If no conflict arrives within 5 seconds, the claim is considered successful.
 */
@Serializable
data class UsernameClaimPayload(
    val username: String,       // already lowercase+trimmed
    val btNodeId: String,
    val wifiNodeId: String,
    val timestamp: Long,
    val nonce: String           // UUID — prevents replay of stale claims
)

/**
 * Sent by a node that already holds [claimedUsername] in its local registry,
 * in response to a [PacketType.USERNAME_CLAIM] it disagrees with.
 */
@Serializable
data class UsernameConflictPayload(
    val claimedUsername: String,
    val existingBtNodeId: String,
    val existingWifiNodeId: String,
    val conflictingNonce: String    // echo of the claim nonce for correlation
)

/**
 * Gossip payload: the sender's full known username → btNodeId registry.
 * Sent when a new peer connects and on each successful claim.
 * Receivers merge with first-seen-wins strategy.
 */
@Serializable
data class UsernameRegistryPayload(
    val registry: Map<String, String>   // username (lowercase) → btNodeId
)

/**
 * Broadcast when a node is resetting its identity and releasing its username.
 */
@Serializable
data class UsernameReleasePayload(
    val username: String,
    val btNodeId: String
)
