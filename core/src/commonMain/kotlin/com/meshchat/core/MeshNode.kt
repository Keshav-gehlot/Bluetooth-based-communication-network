package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MeshNode(
    private val nodeId: String,
    private val config: NodeConfig,
    private val transport: TransportAdapter
) {
    private val deduplicationCache = DeduplicationCache(config.replayWindowMs)
    private val idsMonitor = IdsMonitor(config)

    private lateinit var _receivedPackets: kotlinx.coroutines.channels.ReceiveChannel<Packet>
    val receivedPackets: Flow<Packet> get() = _receivedPackets.receiveAsFlow()

    private lateinit var _anomaliesChannel: kotlinx.coroutines.channels.ReceiveChannel<AnomalyEvent>
    val anomalies: Flow<AnomalyEvent> get() = _anomaliesChannel.receiveAsFlow()

    fun start(scope: CoroutineScope) {
        _anomaliesChannel = scope.produce(capacity = 32) {
            idsMonitor.anomalies.collect { anomaly ->
                send(anomaly)
            }
        }
        _receivedPackets = scope.produce(capacity = 512) {
            transport.incomingPackets.collect { packet ->
                handleIncomingPacket(packet, this)
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: Packet, channel: SendChannel<Packet>) {
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
        channel.send(packet)

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
