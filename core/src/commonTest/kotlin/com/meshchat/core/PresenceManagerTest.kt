package com.meshchat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceManagerTest {

    private lateinit var presenceManager: PresenceManager

    @BeforeTest
    fun setup() {
        presenceManager = PresenceManager()
    }

    @Test
    fun testHandlePresencePacketSuccess() = runTest {
        val payload = PresencePayload(
            nodeId = "peer123",
            displayName = "Test User",
            avatarSeed = "seed123",
            timestamp = 1000L
        )
        val payloadBytes = Json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val packet = Packet(
            id = "packet1",
            type = PacketType.SYSTEM,
            senderId = "peer123",
            payload = payloadBytes,
            timestamp = 1000L,
            hopCount = 1
        )

        presenceManager.handlePresencePacket(packet)

        val peer = presenceManager.peers.value["peer123"]
        assertTrue(peer != null)
        assertEquals("Test User", peer.displayName)
        assertEquals("seed123", peer.avatarSeed)
        assertEquals(1000L, peer.lastSeen)
        assertTrue(peer.isOnline)
        assertEquals(2, peer.hopDistance) // hopCount + 1
    }

    @Test
    fun testHandlePresencePacketIgnoresNonSystemType() = runTest {
        val payload = PresencePayload(
            nodeId = "peer123",
            displayName = "Test User",
            avatarSeed = "seed123",
            timestamp = 1000L
        )
        val payloadBytes = Json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val packet = Packet(
            id = "packet1",
            type = PacketType.CHAT, // non-system type
            senderId = "peer123",
            payload = payloadBytes,
            timestamp = 1000L,
            hopCount = 1
        )

        presenceManager.handlePresencePacket(packet)

        assertNull(presenceManager.peers.value["peer123"])
    }

    @Test
    fun testHandlePresencePacketIgnoresOlderTimestamp() = runTest {
        val payloadNew = PresencePayload(
            nodeId = "peer123",
            displayName = "New User",
            avatarSeed = "seedNew",
            timestamp = 2000L
        )
        val packetNew = Packet(
            id = "packet2",
            type = PacketType.SYSTEM,
            senderId = "peer123",
            payload = Json.encodeToString(payloadNew).toByteArray(Charsets.UTF_8),
            timestamp = 2000L,
            hopCount = 0
        )

        presenceManager.handlePresencePacket(packetNew)

        // Older packet arriving late
        val payloadOld = PresencePayload(
            nodeId = "peer123",
            displayName = "Old User",
            avatarSeed = "seedOld",
            timestamp = 1000L
        )
        val packetOld = Packet(
            id = "packet1",
            type = PacketType.SYSTEM,
            senderId = "peer123",
            payload = Json.encodeToString(payloadOld).toByteArray(Charsets.UTF_8),
            timestamp = 1000L,
            hopCount = 0
        )

        presenceManager.handlePresencePacket(packetOld)

        val peer = presenceManager.peers.value["peer123"]
        assertTrue(peer != null)
        assertEquals("New User", peer.displayName) // still the new user info
        assertEquals(2000L, peer.lastSeen)
    }

    @Test
    fun testSetPeerOffline() = runTest {
        val payload = PresencePayload(
            nodeId = "peer123",
            displayName = "Test User",
            avatarSeed = "seed123",
            timestamp = 1000L
        )
        val packet = Packet(
            id = "packet1",
            type = PacketType.SYSTEM,
            senderId = "peer123",
            payload = Json.encodeToString(payload).toByteArray(Charsets.UTF_8),
            timestamp = 1000L,
            hopCount = 0
        )

        presenceManager.handlePresencePacket(packet)
        assertTrue(presenceManager.peers.value["peer123"]?.isOnline == true)

        presenceManager.setPeerOffline("peer123")
        assertFalse(presenceManager.peers.value["peer123"]?.isOnline == true)
    }
}
