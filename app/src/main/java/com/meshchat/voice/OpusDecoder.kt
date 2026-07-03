package com.meshchat.voice

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

class OpusDecoder {
    private var codec: MediaCodec? = null

    fun start(format: MediaFormat) {
        codec = MediaCodec.createDecoderByType("audio/opus").apply {
            configure(format, null, null, 0)
            start()
        }
    }

    fun decodeFrame(opusData: ByteArray?): ByteArray {
        val c = codec ?: return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)

        if (opusData == null) {
            // Packet Loss Concealment (PLC): return silence for prototype
            return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
        }

        val inIdx = c.dequeueInputBuffer(0L)
        if (inIdx < 0) return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
        val inBuf = c.getInputBuffer(inIdx) ?: return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
        inBuf.clear()
        inBuf.put(opusData)
        c.queueInputBuffer(
            inIdx,
            0,
            opusData.size,
            System.nanoTime() / 1000,
            0
        )

        val info = MediaCodec.BufferInfo()
        val outIdx = c.dequeueOutputBuffer(info, 2000L)  // 2ms max wait
        if (outIdx < 0) return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
        val outBuf = c.getOutputBuffer(outIdx) ?: return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
        val pcm = ByteArray(info.size)
        outBuf.get(pcm)
        c.releaseOutputBuffer(outIdx, false)
        return pcm
    }

    fun stop() {
        runCatching {
            codec?.stop()
        }
        runCatching {
            codec?.release()
        }
        codec = null
    }
}
