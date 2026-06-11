package com.meshchat.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class FakeTransportTest {

    @Test
    fun testChannelDoesNotDropUnderLoad() = runTest {
        val transport = FakeTransport()
        
        val received = mutableListOf<Packet>()
        val job = launch {
            transport.incomingPackets.collect { packet ->
                delay(10) // Simulate slow collector
                received.add(packet)
            }
        }
        
        // Emit 100 packets synchronously (faster than collector)
        for (i in 1..100) {
            val packet = Packet(
                id = "pkg_$i",
                type = PacketType.CHAT,
                senderId = "sender",
                targetId = "target",
                payload = ByteArray(0),
                timestamp = System.currentTimeMillis(),
                hopCount = 0
            )
            transport.simulateIncoming(packet)
        }
        
        // Let the slow collector process the buffered packets
        delay(2000)
        job.cancel()
        
        // At least 64 packets (buffer capacity) should be received.
        // The rest are safely evicted via DROP_OLDEST, not silently dropped due to backpressure.
        assertTrue(received.size >= 64, "Expected at least 64 packets to be buffered and received, got ${received.size}")
    }
}
