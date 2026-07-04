package com.meshchat.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class IdsMonitor(
    private val config: NodeConfig
) {
    private val _anomalies = Channel<AnomalyEvent>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val anomalies: Flow<AnomalyEvent> = _anomalies.receiveAsFlow()

    private val mutex = Mutex()
    private val rateWindows = mutableMapOf<String, ArrayDeque<Long>>()
    private val quarantine = mutableMapOf<String, Long>()

    companion object {
        private const val QUARANTINE_DURATION_MS = 30_000L // 30 seconds
    }

    enum class InspectResult { PASS, QUARANTINED }

    // LOCK ORDER: this is the only lock held at this call site
    suspend fun inspect(packet: Packet): InspectResult = mutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        
        val quarantinedAt = quarantine[packet.senderId]
        if (quarantinedAt != null) {
            if (now - quarantinedAt < QUARANTINE_DURATION_MS) {
                return@withLock InspectResult.QUARANTINED
            } else {
                quarantine.remove(packet.senderId) // Quarantine expired
            }
        }

        val window = rateWindows.getOrPut(packet.senderId) { ArrayDeque() }
        window.addLast(now)
        while (window.isNotEmpty() && now - window.first() > 1000L) {
            window.removeFirst()
        }

        if (window.size > config.maxPacketsPerSecond) {
            quarantine[packet.senderId] = now
            _anomalies.trySend(AnomalyEvent.RateLimitExceeded(packet.senderId))
            return@withLock InspectResult.QUARANTINED
        }
        return@withLock InspectResult.PASS
    }

    fun reportReplayAttack(packetId: String, senderId: String) {
        _anomalies.trySend(AnomalyEvent.ReplayAttack(packetId, senderId))
    }

    fun reportHopLimitExceeded(packetId: String) {
        _anomalies.trySend(AnomalyEvent.HopLimitExceeded(packetId))
    }
}
