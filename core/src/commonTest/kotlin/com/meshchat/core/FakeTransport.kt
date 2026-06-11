package com.meshchat.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeTransport : TransportAdapter {
    private val _incomingChannel = Channel<Packet>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingPackets: Flow<Packet> = _incomingChannel.receiveAsFlow()

    val sentPackets = mutableListOf<Packet>()

    override suspend fun sendPacket(packet: Packet) {
        sentPackets.add(packet)
    }

    override fun start(localNodeId: String) {}
    override fun stop() {
        _incomingChannel.close()
    }

    suspend fun simulateIncoming(packet: Packet) {
        _incomingChannel.trySend(packet)
    }
}
