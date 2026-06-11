package com.meshchat.core

import kotlinx.coroutines.flow.Flow

interface TransportAdapter {
    val incomingPackets: Flow<Packet>
    suspend fun sendPacket(packet: Packet)
    fun start(localNodeId: String)
    fun stop()
}
