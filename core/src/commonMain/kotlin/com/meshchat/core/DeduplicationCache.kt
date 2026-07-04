package com.meshchat.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class DeduplicationCache(
    private val replayWindowMs: Long
) {
    private val mutex = Mutex()
    private val seenPackets = mutableMapOf<String, Long>()
    private var callCount = 0

    suspend fun isDuplicateOrReplay(packetId: String): Boolean = mutex.withLock {
        if (++callCount % 50 == 0) {
            cleanup()
        }
        return@withLock if (seenPackets.containsKey(packetId)) {
            true
        } else {
            seenPackets[packetId] = Clock.System.now().toEpochMilliseconds()
            false
        }
    }

    suspend fun clear() = mutex.withLock {
        seenPackets.clear()
    }

    private fun cleanup() {
        val threshold = Clock.System.now().toEpochMilliseconds() - replayWindowMs
        val iterator = seenPackets.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < threshold) {
                iterator.remove()
            }
        }
        if (seenPackets.size > 512) {
            val firstKey = seenPackets.keys.firstOrNull()
            if (firstKey != null) {
                seenPackets.remove(firstKey)
            }
        }
    }
}
