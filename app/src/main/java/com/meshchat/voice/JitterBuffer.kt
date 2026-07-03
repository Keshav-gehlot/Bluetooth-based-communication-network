package com.meshchat.voice

import android.util.Base64
import com.meshchat.core.VoiceFramePayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeMap

class JitterBuffer(
    private val minDepthMs: Int = VoiceBitratePolicy.JITTER_MIN_MS,
    private val maxDepthMs: Int = VoiceBitratePolicy.JITTER_MAX_MS,
) {
    private val mutex = Mutex()
    private val buffer = TreeMap<Int, VoiceFramePayload>()
    private var nextExpectedSeq = 0
    private var targetDepthMs = minDepthMs
    private var consecutiveLate = 0
    private var consecutiveOnTime = 0

    suspend fun push(frame: VoiceFramePayload): Boolean = mutex.withLock {
        // Handle buffer fill check frame
        if (frame.opusData.isEmpty()) {
            val bufferedFrames = buffer.size
            val bufferedMs = bufferedFrames * 20
            return@withLock bufferedMs >= targetDepthMs
        }

        buffer[frame.seq] = frame

        // Adaptive: if frames arriving late (lower than nextExpectedSeq), increase buffer depth
        if (frame.seq < nextExpectedSeq) {
            consecutiveLate++
            consecutiveOnTime = 0
            if (consecutiveLate > 3) {
                targetDepthMs = (targetDepthMs + 20).coerceAtMost(maxDepthMs)
                consecutiveLate = 0
            }
        } else {
            consecutiveOnTime++
            consecutiveLate = 0
            if (consecutiveOnTime > 10) {
                targetDepthMs = (targetDepthMs - 10).coerceAtLeast(minDepthMs)
                consecutiveOnTime = 0
            }
        }

        val bufferedFrames = buffer.size
        val bufferedMs = bufferedFrames * 20  // 20ms per frame
        return@withLock bufferedMs >= targetDepthMs
    }

    suspend fun pull(): ByteArray? = mutex.withLock {
        val frame = buffer.remove(nextExpectedSeq)
        nextExpectedSeq++
        return@withLock frame?.let {
            if (it.opusData.isEmpty()) null
            else Base64.decode(it.opusData, Base64.NO_WRAP)
        }
    }

    suspend fun size(): Int = mutex.withLock { buffer.size }

    suspend fun clear() = mutex.withLock {
        buffer.clear()
        nextExpectedSeq = 0
        targetDepthMs = minDepthMs
        consecutiveLate = 0
        consecutiveOnTime = 0
    }
}
