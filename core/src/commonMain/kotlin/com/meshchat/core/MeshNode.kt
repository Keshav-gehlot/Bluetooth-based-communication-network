package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch

class MeshNode(
    private val nodeId: String,
    private val config: NodeConfig,
    private val transport: TransportAdapter
) {
    private val deduplicationCache = DeduplicationCache(config.replayWindowMs)
    private val idsMonitor = IdsMonitor(config)

    private val _receivedPackets = MutableSharedFlow<Packet>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedPackets: Flow<Packet> = _receivedPackets.asSharedFlow()

    private val _anomalies = MutableSharedFlow<AnomalyEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val anomalies: Flow<AnomalyEvent> = _anomalies.asSharedFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            idsMonitor.anomalies.collect { anomaly ->
                _anomalies.emit(anomaly)
            }
        }
        scope.launch {
            transport.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: Packet) {
        // 1. Deduplication & Replay Check
        if (deduplicationCache.isDuplicateOrReplay(packet.id)) {
            if (config.idsEnabled) {
                idsMonitor.reportReplayAttack(packet.id, packet.senderId)
            }
            return
        }

        // 2. Hop Limit Check
        if (packet.hopCount >= config.maxHops) {
            if (config.idsEnabled) {
                idsMonitor.reportHopLimitExceeded(packet.id)
            }
            return
        }

        // 3. Rate Limit Check
        if (config.idsEnabled) {
            val idsResult = idsMonitor.inspect(packet)
            if (idsResult == IdsMonitor.InspectResult.QUARANTINED) {
                return // Drop due to rate limiting
            }
        }

        // 4. Accept the packet for processing
        _receivedPackets.emit(packet)

        // 5. Flood forwarding (if not targeted strictly to us)
        if (packet.targetId == null || packet.targetId != nodeId) {
            val forwardedPacket = packet.copy(hopCount = packet.hopCount + 1)
            transport.sendPacket(forwardedPacket)
        }
    }

    suspend fun sendPacket(packet: Packet) {
        // We add our own packet to deduplication cache so we don't process our own echos
        deduplicationCache.isDuplicateOrReplay(packet.id)
        transport.sendPacket(packet)
    }
}
