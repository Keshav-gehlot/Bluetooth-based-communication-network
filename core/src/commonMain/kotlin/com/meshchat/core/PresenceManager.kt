package com.meshchat.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class PeerInfo(
    val displayName: String,
    val avatarSeed: String,
    val lastSeen: Long,
    val isOnline: Boolean,
    val hopDistance: Int
)

@Serializable
data class PresencePayload(
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    val timestamp: Long
)

class PresenceManager {
    private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val peers: StateFlow<Map<String, PeerInfo>> = _peers.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun handlePresencePacket(packet: Packet) {
        if (packet.type != PacketType.SYSTEM) return
        try {
            val jsonString = String(packet.payload, Charsets.UTF_8)
            val presence = json.decodeFromString<PresencePayload>(jsonString)
            
            val hopDistance = packet.hopCount + 1
            
            // Only update if the peer information is newer or we didn't have it
            val existing = _peers.value[presence.nodeId]
            if (existing == null || existing.lastSeen <= presence.timestamp) {
                _peers.value = _peers.value + (presence.nodeId to PeerInfo(
                    displayName = presence.displayName,
                    avatarSeed = presence.avatarSeed,
                    lastSeen = presence.timestamp,
                    isOnline = true,
                    hopDistance = hopDistance
                ))
            }
        } catch (e: Exception) {
            // Ignore malformed packet
        }
    }

    fun setPeerOffline(nodeId: String) {
        val currentPeer = _peers.value[nodeId] ?: return
        _peers.value = _peers.value + (nodeId to currentPeer.copy(
            isOnline = false,
            lastSeen = System.currentTimeMillis()
        ))
    }
}
