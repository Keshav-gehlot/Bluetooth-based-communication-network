package com.meshchat.core

import kotlinx.serialization.Serializable

/**
 * The permanent dual-transport identity for a node on the MeshChat network.
 *
 * - [btNodeId] and [wifiNodeId] are generated ONCE on first launch and NEVER regenerated.
 * - [username] is mesh-unique and claimed via [UsernameClaimProtocol].
 * - [avatarSeed] always equals [btNodeId] for consistency across both transports.
 */
@Serializable
data class NodeIdentity(
    val username: String,           // "Keshav" — human name, mesh-unique
    val btNodeId: String,           // "BT-A3F2-9D12" — permanent, BT mesh
    val wifiNodeId: String,         // "WIFI-C8B1-4E33" — permanent, WiFi mesh
    val avatarSeed: String,         // deterministic color seed (= btNodeId)
    val createdAt: Long,
    val usernameClaimed: Boolean    // false until mesh confirms unique
)
