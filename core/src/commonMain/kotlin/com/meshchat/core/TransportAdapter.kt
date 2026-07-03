package com.meshchat.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TransportAdapter {
    /** Live set of currently-connected peer node IDs. */
    val peersFlow: StateFlow<Set<String>>

    /** Stream of raw incoming packets from any connected peer. */
    val incomingPackets: Flow<Packet>

    suspend fun sendPacket(packet: Packet)
    fun start(localNodeId: String)
    fun stop()
}

