package com.meshchat.voice

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

class OpusEncoder(private val bitrate: Int) {
    private var codec: MediaCodec? = null

    fun start() {
        codec = MediaCodec.createEncoderByType("audio/opus").apply {
            val format = MediaFormat.createAudioFormat(
                "audio/opus",
                VoiceBitratePolicy.SAMPLE_RATE,
                VoiceBitratePolicy.CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VoiceBitratePolicy.FRAME_SIZE_BYTES * 2)
                setInteger(MediaFormat.KEY_LATENCY, 0)  // low-latency mode
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    fun encodePcmFrame(pcm: ByteArray): ByteArray? {
        val c = codec ?: return null
        val inIdx = c.dequeueInputBuffer(0L)  // non-blocking
        if (inIdx < 0) return null
        val inBuf = c.getInputBuffer(inIdx) ?: return null
        inBuf.clear()
        inBuf.put(pcm)
        c.queueInputBuffer(
            inIdx,
            0,
            pcm.size,
            System.nanoTime() / 1000,
            0
        )

        val info = MediaCodec.BufferInfo()
        val outIdx = c.dequeueOutputBuffer(info, 0L)
        if (outIdx < 0) return null
        val outBuf = c.getOutputBuffer(outIdx) ?: return null
        val encoded = ByteArray(info.size)
        outBuf.get(encoded)
        c.releaseOutputBuffer(outIdx, false)
        return encoded
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
