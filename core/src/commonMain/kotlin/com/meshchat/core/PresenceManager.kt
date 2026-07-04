package com.meshchat.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

// ── Presence payload (sent over the wire) ─────────────────────────────────────

@Serializable
data class PresencePayload(
    val username: String,
    val btNodeId: String,
    val wifiNodeId: String,
    val avatarSeed: String,
    val activeTransport: TransportMode,
    val timestamp: Long,
    val usernameClaimed: Boolean,
)

// ── Peer info (local model) ────────────────────────────────────────────────────

data class PeerInfo(
    val username: String,
    val btNodeId: String,
    val wifiNodeId: String,
    val avatarSeed: String,
    val isOnline: Boolean,
    val activeTransport: TransportMode,
    val hopDistance: Int,
    val lastSeen: Long,
    val usernameClaimed: Boolean,
)

// ── Manager ────────────────────────────────────────────────────────────────────

class PresenceManager {

    private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    /** Live map of username → PeerInfo for all known peers. */
    val peers: StateFlow<Map<String, PeerInfo>> = _peers.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /** Route an incoming packet to the appropriate handler. */
    fun handlePresencePacket(packet: Packet) {
        if (packet.type != PacketType.SYSTEM && packet.type != PacketType.PRESENCE) return
        try {
            val jsonString = String(packet.payload, Charsets.UTF_8)
            val presence   = json.decodeFromString<PresencePayload>(jsonString)
            val hopDistance = packet.hopCount + 1

            val key = presence.username.lowercase().trim()
            val existing = _peers.value[key]
            if (existing == null || existing.lastSeen <= presence.timestamp) {
                _peers.update { current ->
                    current + (key to PeerInfo(
                        username        = presence.username,
                        btNodeId        = presence.btNodeId,
                        wifiNodeId      = presence.wifiNodeId,
                        avatarSeed      = presence.avatarSeed,
                        isOnline        = true,
                        activeTransport = presence.activeTransport,
                        hopDistance     = hopDistance,
                        lastSeen        = presence.timestamp,
                        usernameClaimed = presence.usernameClaimed,
                    ))
                }
            }
        } catch (e: Exception) {
            // Ignore malformed presence packets
        }
    }

    // ── Offline marking ────────────────────────────────────────────────────────

    /** Mark a peer offline by their BT node ID. */
    fun setPeerOffline(btNodeId: String) {
        val entry = _peers.value.entries.firstOrNull { it.value.btNodeId == btNodeId } ?: return
        _peers.update { current ->
            current + (entry.key to entry.value.copy(
                isOnline = false,
                lastSeen = System.currentTimeMillis(),
            ))
        }
    }

    /** Mark a peer offline by their WiFi node ID. */
    fun setWifiPeerOffline(wifiNodeId: String) {
        val entry = _peers.value.entries.firstOrNull { it.value.wifiNodeId == wifiNodeId } ?: return
        _peers.update { current ->
            current + (entry.key to entry.value.copy(
                isOnline = false,
                lastSeen = System.currentTimeMillis(),
            ))
        }
    }

    // ── Lookup ─────────────────────────────────────────────────────────────────

    fun getPeerByBtId(btNodeId: String): PeerInfo? =
        _peers.value.values.firstOrNull { it.btNodeId == btNodeId }

    fun getPeerByWifiId(wifiNodeId: String): PeerInfo? =
        _peers.value.values.firstOrNull { it.wifiNodeId == wifiNodeId }

    fun getPeerByUsername(username: String): PeerInfo? =
        _peers.value[username.lowercase().trim()]
}

