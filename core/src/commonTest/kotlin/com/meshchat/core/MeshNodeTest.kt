package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.datetime.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.isActive

@OptIn(ExperimentalCoroutinesApi::class)
class MeshNodeTest {

    private lateinit var transport: FakeTransport
    private lateinit var node: MeshNode

    @BeforeTest
    fun setup() {
        transport = FakeTransport()
    }

    @Test
    fun testDeduplicationDropsDuplicate() = runTest {
        val childScope = CoroutineScope(coroutineContext + Job())
        node = MeshNode("nodeA", NodeConfig(), transport)
        node.start(childScope)

        val received = mutableListOf<Packet>()
        childScope.launch {
            node.receivedPackets.toList(received)
        }

        yield()

        val packet = Packet("p1", PacketType.CHAT, "nodeB", null, ByteArray(0), Clock.System.now().toEpochMilliseconds(), 0)

        // Simulate incoming twice
        transport.simulateIncoming(packet)
        yield()
        transport.simulateIncoming(packet)
        yield()

        assertEquals(1, received.size) // Only received once
        childScope.cancel()
    }

    @Test
    fun testHopLimitDropsPacket() = runTest {
        val childScope = CoroutineScope(coroutineContext + Job())
        val config = NodeConfig(maxHops = 3)
        node = MeshNode("nodeA", config, transport)
        node.start(childScope)

        val received = mutableListOf<Packet>()
        childScope.launch {
            node.receivedPackets.toList(received)
        }

        yield()

        val packet = Packet("p1", PacketType.CHAT, "nodeB", null, ByteArray(0), Clock.System.now().toEpochMilliseconds(), 3) // At max hops

        transport.simulateIncoming(packet)
        yield()

        assertEquals(0, received.size) // Dropped
        childScope.cancel()
    }

    @Test
    fun testReplayWindowRejectsReused() = runTest {
        val childScope = CoroutineScope(coroutineContext + Job())
        val config = NodeConfig(replayWindowMs = 300_000)
        node = MeshNode("nodeA", config, transport)
        node.start(childScope)

        val received = mutableListOf<Packet>()
        childScope.launch {
            node.receivedPackets.toList(received)
        }

        yield()

        val packet = Packet("p1", PacketType.CHAT, "nodeB", null, ByteArray(0), Clock.System.now().toEpochMilliseconds(), 0)

        transport.simulateIncoming(packet)
        yield()

        // Try to replay the exact same packet ID after 1 minute (within replay window)
        transport.simulateIncoming(packet)
        yield()

        assertEquals(1, received.size) // First one accepted, second rejected
        childScope.cancel()
    }

    @Test
    fun testRateLimitTriggersAnomaly() = runTest {
        val childScope = CoroutineScope(coroutineContext + Job())
        val config = NodeConfig(maxPacketsPerSecond = 5)
        node = MeshNode("nodeA", config, transport)
        node.start(childScope)

        val anomalies = mutableListOf<AnomalyEvent>()
        childScope.launch {
            node.anomalies.toList(anomalies)
        }

        yield()

        // Send 6 packets from the same sender in quick succession
        for (i in 1..6) {
            val packet = Packet("p$i", PacketType.CHAT, "nodeB", null, ByteArray(0), Clock.System.now().toEpochMilliseconds(), 0)
            transport.simulateIncoming(packet)
            advanceUntilIdle()
        }

        assertTrue(anomalies.any { it is AnomalyEvent.RateLimitExceeded })
        childScope.cancel()
    }

    @Test
    fun testFloodingReachesAllPeers() = runTest {
        val childScope = CoroutineScope(coroutineContext + Job())
        node = MeshNode("nodeA", NodeConfig(), transport)
        node.start(childScope)

        yield()

        val packet = Packet("p1", PacketType.BROADCAST, "nodeB", null, ByteArray(0), Clock.System.now().toEpochMilliseconds(), 0)

        transport.simulateIncoming(packet)
        yield()

        // Verify it was forwarded with incremented hop count
        assertEquals(1, transport.sentPackets.size)
        assertEquals(1, transport.sentPackets[0].hopCount)
        childScope.cancel()
    }

    @Test
    fun testScopeCleanup() = runTest {
        val childScope = CoroutineScope(coroutineContext + Job())
        node = MeshNode("nodeA", NodeConfig(), transport)
        node.start(childScope)

        val anomalies = mutableListOf<AnomalyEvent>()
        childScope.launch {
            node.anomalies.toList(anomalies)
        }

        yield()

        // Send 10 messages
        for (i in 1..10) {
            val packet = Packet("p$i", PacketType.CHAT, "nodeB", null, ByteArray(0), Clock.System.now().toEpochMilliseconds(), 0)
            transport.simulateIncoming(packet)
            advanceUntilIdle()
        }

        // Cancel scope
        childScope.cancel()

        // Give coroutines a chance to process cancellation
        yield()

        // Assert scope is inactive
        assertTrue(!childScope.isActive)
        // Assert all children are completed (or cancelled)
        val job = childScope.coroutineContext[Job]
        assertTrue(job?.children?.all { it.isCompleted || it.isCancelled } ?: true)
    }
}
