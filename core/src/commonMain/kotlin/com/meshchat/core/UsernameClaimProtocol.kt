package com.meshchat.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Distributed username reservation protocol.
 * No central server — uniqueness is enforced by mesh consensus.
 *
 * Claim flow:
 * 1. Check local registry instantly (fail fast if name known-taken).
 * 2. Broadcast [PacketType.USERNAME_CLAIM] to mesh.
 * 3. Listen for [PacketType.USERNAME_CONFLICT] for up to 5 seconds.
 * 4. If no conflict: mark claimed, broadcast registry.
 *
 * All username comparisons use [String.lowercase] + [String.trim].
 * Max length: 24 chars. Regex: ^[a-zA-Z0-9_-]{1,24}$
 */
class UsernameClaimProtocol(
    private val bridge: UsernameClaimBridge,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private var meshNode: MeshNode? = null

    fun setMeshNode(node: MeshNode) {
        this.meshNode = node
    }

    // Local registry: username (lowercase) → btNodeId
    private val registry = mutableMapOf<String, String>()

    // ── Result types ───────────────────────────────────────────────────────────
    sealed class ClaimResult {
        object Claimed : ClaimResult()
        data class Conflict(val takenBy: String) : ClaimResult()
        object Timeout : ClaimResult()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Attempt to claim [username] for this node.
     * Must be called after [MeshNode.start].
     */
    suspend fun claimUsername(username: String, identity: NodeIdentity): ClaimResult {
        val normalized = username.lowercase().trim()

        // Step 1: instant local check
        mutex.withLock {
            val existing = registry[normalized]
            if (existing != null && existing != identity.btNodeId) {
                return ClaimResult.Conflict(takenBy = existing)
            }
        }

        val node = meshNode ?: return ClaimResult.Timeout

        // Step 2: broadcast CLAIM
        val nonce = UUID.randomUUID().toString()
        val payload = UsernameClaimPayload(
            username    = normalized,
            btNodeId    = identity.btNodeId,
            wifiNodeId  = identity.wifiNodeId,
            timestamp   = System.currentTimeMillis(),
            nonce       = nonce,
        )
        node.sendPacket(
            Packet(
                id        = UUID.randomUUID().toString(),
                type      = PacketType.USERNAME_CLAIM,
                senderId  = identity.btNodeId,
                targetId  = null,   // broadcast
                payload   = json.encodeToString(payload).encodeToByteArray(),
                timestamp = System.currentTimeMillis(),
            )
        )

        // Step 3: wait up to 5 s for a CONFLICT matching our nonce
        val conflictResult = withTimeoutOrNull(5_000L) {
            node.receivedPackets
                .filter { it.type == PacketType.USERNAME_CONFLICT }
                .map { json.decodeFromString<UsernameConflictPayload>(String(it.payload)) }
                .filter { it.conflictingNonce == nonce }
                .first()
                .let { ClaimResult.Conflict(takenBy = it.existingBtNodeId) }
        }

        return if (conflictResult != null) {
            conflictResult
        } else {
            // No conflict — name is ours
            mutex.withLock { registry[normalized] = identity.btNodeId }
            bridge.markUsernameClaimed()
            broadcastRegistry(identity.btNodeId)
            ClaimResult.Claimed
        }
    }

    /**
     * Must be called by the packet dispatcher when a [PacketType.USERNAME_CLAIM] arrives.
     */
    suspend fun onClaimReceived(packet: Packet) {
        val payload = runCatching {
            json.decodeFromString<UsernameClaimPayload>(String(packet.payload))
        }.getOrNull() ?: return

        val conflictingNodeId = mutex.withLock {
            val existing = registry[payload.username]
            if (existing != null && existing != payload.btNodeId) {
                existing    // conflict: we know this name is taken
            } else {
                // New claim — register and flood
                registry[payload.username] = payload.btNodeId
                null
            }
        }

        val node = meshNode ?: return

        if (conflictingNodeId != null) {
            node.sendPacket(
                Packet(
                    id        = UUID.randomUUID().toString(),
                    type      = PacketType.USERNAME_CONFLICT,
                    senderId  = bridge.getActiveNodeId(TransportMode.BLUETOOTH),
                    targetId  = payload.btNodeId,
                    payload   = json.encodeToString(
                        UsernameConflictPayload(
                            claimedUsername   = payload.username,
                            existingBtNodeId  = conflictingNodeId,
                            existingWifiNodeId = "",
                            conflictingNonce  = payload.nonce,
                        )
                    ).encodeToByteArray(),
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Gossip the full registry to a newly connected peer.
     */
    suspend fun onPeerConnected(peerId: String, localBtNodeId: String) {
        val snapshot = mutex.withLock { registry.toMap() }
        if (snapshot.isEmpty()) return
        val node = meshNode ?: return
        node.sendPacket(
            Packet(
                id        = UUID.randomUUID().toString(),
                type      = PacketType.USERNAME_REGISTRY,
                senderId  = localBtNodeId,
                targetId  = peerId,
                payload   = json.encodeToString(UsernameRegistryPayload(registry = snapshot))
                    .encodeToByteArray(),
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Merge an incoming registry from another node (first-seen-wins).
     */
    suspend fun onRegistryReceived(packet: Packet) {
        val payload = runCatching {
            json.decodeFromString<UsernameRegistryPayload>(String(packet.payload))
        }.getOrNull() ?: return

        mutex.withLock {
            payload.registry.forEach { (username, nodeId) ->
                if (!registry.containsKey(username)) {
                    registry[username] = nodeId
                }
            }
        }
    }

    /**
     * Remove a username from the local registry when a node releases it.
     */
    suspend fun onReleaseReceived(packet: Packet) {
        val payload = runCatching {
            json.decodeFromString<UsernameReleasePayload>(String(packet.payload))
        }.getOrNull() ?: return

        mutex.withLock {
            if (registry[payload.username] == payload.btNodeId) {
                registry.remove(payload.username)
            }
        }
    }

    /** Lookup: is this username already claimed by a known node? */
    suspend fun isUsernameTaken(username: String): Boolean = mutex.withLock {
        registry.containsKey(username.lowercase().trim())
    }

    /** Lookup: btNodeId for a given username, or null if unknown. */
    suspend fun getBtNodeIdForUsername(username: String): String? = mutex.withLock {
        registry[username.lowercase().trim()]
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private suspend fun broadcastRegistry(localBtNodeId: String) {
        val snapshot = mutex.withLock { registry.toMap() }
        val node = meshNode ?: return
        node.sendPacket(
            Packet(
                id        = UUID.randomUUID().toString(),
                type      = PacketType.USERNAME_REGISTRY,
                senderId  = localBtNodeId,
                targetId  = null,   // broadcast
                payload   = json.encodeToString(UsernameRegistryPayload(registry = snapshot))
                    .encodeToByteArray(),
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        /** Validation regex for usernames. Max 24 chars, letters/digits/underscore/hyphen only. */
        val USERNAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,24}$")

        fun isValidUsername(username: String): Boolean =
            username.matches(USERNAME_REGEX)
    }
}
