package com.meshchat.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Base64
import com.meshchat.core.*
import com.meshchat.data.database.MessageDao
import com.meshchat.data.database.MessageEntity
import com.meshchat.domain.model.VoiceSession
import com.meshchat.domain.model.VoiceSessionState
import com.meshchat.domain.repository.IdentityRepository
import com.meshchat.transports.DualTransportManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meshNode: MeshNode,
    private val transport: DualTransportManager,
    private val identityRepository: IdentityRepository,
    private val audioFocusManager: AudioFocusManager,
    private val messageDao: MessageDao
) : VoiceBridge {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _sessionFlow = MutableSharedFlow<VoiceSession>(
        replay = 1, extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sessionFlow: Flow<VoiceSession> = _sessionFlow.asSharedFlow()

    private val _activeSession = MutableStateFlow<String?>(null)
    val activeSession: StateFlow<String?> = _activeSession.asStateFlow()

    private var isTransmitting = false
    private var transmitStartTime = 0L
    private var transmitSeq = 0

    // Single-thread dispatchers for isolated execution of blocking audio calls
    private val audioCaptureDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MeshChat-AudioCapture").apply {
            priority = Thread.MAX_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val audioPlaybackDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MeshChat-AudioPlayback").apply {
            priority = Thread.MAX_PRIORITY
        }
    }.asCoroutineDispatcher()

    // Half-duplex channel occupation flow
    val isChannelBusy: StateFlow<Boolean> = sessionFlow
        .map { session ->
            session.state == VoiceSessionState.Receiving || session.state == VoiceSessionState.Transmitting
        }
        .stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, false)

    // Start transmission (PTT Pressed)
    suspend fun startTransmitting(
        dstUsername: String,
        conversationId: String,
        scope: CoroutineScope
    ) {
        if (isTransmitting) return
        if (_activeSession.value != null) {
            // Someone else is speaking — reject and emit BUSY
            _sessionFlow.emit(
                VoiceSession(
                    sessionId = "",
                    senderUsername = "",
                    conversationId = conversationId,
                    state = VoiceSessionState.Busy,
                    durationMs = 0,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = true
                )
            )
            return
        }

        // Acquire Audio Focus
        val focusGranted = audioFocusManager.requestVoiceTransmitFocus {
            scope.launch {
                stopTransmitting(dstUsername, conversationId)
            }
        }
        if (!focusGranted) {
            _sessionFlow.emit(
                VoiceSession(
                    sessionId = "",
                    senderUsername = "",
                    conversationId = conversationId,
                    state = VoiceSessionState.Busy,
                    durationMs = 0,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = true
                )
            )
            return
        }

        val sessionId = UUID.randomUUID().toString()
        if (!_activeSession.compareAndSet(null, sessionId)) {
            audioFocusManager.abandonFocus()
            return
        }

        val mode = identityRepository.getTransportMode().first()
        val bitrate = VoiceBitratePolicy.opusBitrate(mode)
        isTransmitting = true
        transmitStartTime = System.currentTimeMillis()
        transmitSeq = 0

        // 1. Emit Transmitting state
        _sessionFlow.emit(
            VoiceSession(
                sessionId = sessionId,
                senderUsername = meshNode.localUsername,
                conversationId = conversationId,
                state = VoiceSessionState.Transmitting,
                durationMs = 0,
                timestamp = transmitStartTime,
                isOutgoing = true
            )
        )

        // 2. Send VOICE_START
        val startPayload = VoiceStartPayload(
            sessionId = sessionId,
            senderUsername = meshNode.localUsername,
            senderNodeId = meshNode.localNodeId,
            conversationId = conversationId,
            opusBitrate = bitrate,
            timestamp = transmitStartTime
        )
        meshNode.send(
            dst = dstUsername,
            payload = Json.encodeToString(startPayload).encodeToByteArray(),
            type = PacketType.VOICE_START
        )

        val encoder = OpusEncoder(bitrate).also { it.start() }

        // Setup AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(
            VoiceBitratePolicy.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize.coerceAtLeast(VoiceBitratePolicy.FRAME_SIZE_BYTES * 4)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            VoiceBitratePolicy.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val captureChannel = Channel<ByteArray>(
            capacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        // Capture loop on dedicated thread
        scope.launch(audioCaptureDispatcher) {
            try {
                audioRecord.startRecording()
                val pcmBuffer = ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
                while (isTransmitting && isActive) {
                    val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        captureChannel.trySend(pcmBuffer.copyOf())
                    }
                }
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                captureChannel.close()
            }
        }

        // Encode and send loop
        scope.launch(Dispatchers.Default) {
            try {
                for (pcm in captureChannel) {
                    val opus = encoder.encodePcmFrame(pcm) ?: continue
                    val timestampMs = (System.currentTimeMillis() - transmitStartTime).toInt()
                    val framePayload = VoiceFramePayload(
                        sessionId = sessionId,
                        seq = transmitSeq++,
                        timestampMs = timestampMs,
                        opusData = Base64.encodeToString(opus, Base64.NO_WRAP)
                    )
                    meshNode.send(
                        dst = dstUsername,
                        payload = Json.encodeToString(framePayload).encodeToByteArray(),
                        type = PacketType.VOICE_FRAME
                    )
                }
            } finally {
                encoder.stop()
            }
        }
    }

    // Stop transmission (PTT Released)
    suspend fun stopTransmitting(dstUsername: String, conversationId: String) {
        if (!isTransmitting) return
        isTransmitting = false
        val sessionId = _activeSession.value ?: return
        val durationMs = System.currentTimeMillis() - transmitStartTime

        // Send VOICE_END
        val endPayload = VoiceEndPayload(
            sessionId = sessionId,
            finalSeq = transmitSeq,
            durationMs = durationMs
        )
        meshNode.send(
            dst = dstUsername,
            payload = Json.encodeToString(endPayload).encodeToByteArray(),
            type = PacketType.VOICE_END
        )

        _activeSession.value = null
        audioFocusManager.abandonFocus()

        // Save Voice Message to Database
        val text = "[Voice Message] $durationMs"
        val message = MessageEntity(
            id = sessionId,
            text = text,
            senderId = meshNode.localUsername,
            senderName = meshNode.localUsername,
            timestamp = transmitStartTime,
            isOutgoing = true,
            status = "DELIVERED",
            hopCount = 0,
            conversationId = conversationId
        )
        withContext(Dispatchers.IO) {
            messageDao.insertMessage(message)
        }

        _sessionFlow.emit(
            VoiceSession(
                sessionId = sessionId,
                senderUsername = meshNode.localUsername,
                conversationId = conversationId,
                state = VoiceSessionState.Completed(durationMs),
                durationMs = durationMs,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )
    }

    // --- RECEIVE ---
    private val JitterBuffer = JitterBuffer()
    private val decoder = OpusDecoder()
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val receiveScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var receivingSenderUsername = "Peer"

    // 1. Voice start packet callback
    override suspend fun onVoiceStart(payload: VoiceStartPayload) {
        if (!_activeSession.compareAndSet(null, payload.sessionId)) {
            // Already busy with another session — send busy packet
            meshNode.send(
                dst = payload.senderUsername,
                payload = ByteArray(0),
                type = PacketType.VOICE_BUSY
            )
            return
        }

        receivingSenderUsername = payload.senderUsername
        JitterBuffer.clear()
        decoder.start(
            MediaFormat.createAudioFormat(
                "audio/opus",
                payload.sampleRate,
                payload.channels
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, payload.opusBitrate)
            }
        )

        // Route speaker phone mode
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            VoiceBitratePolicy.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize.coerceAtLeast(VoiceBitratePolicy.FRAME_SIZE_BYTES * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(VoiceBitratePolicy.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        _sessionFlow.emit(
            VoiceSession(
                sessionId = payload.sessionId,
                senderUsername = payload.senderUsername,
                conversationId = payload.conversationId,
                state = VoiceSessionState.Receiving,
                durationMs = 0,
                timestamp = payload.timestamp,
                isOutgoing = false
            )
        )

        // Wait for Jitter Buffer before starting playback
        playbackJob = receiveScope.launch(Dispatchers.Default) {
            while (!JitterBuffer.push(VoiceFramePayload(payload.sessionId, 0, 0, "")) && isActive) {
                delay(5)
            }
            startPlayback()
        }
    }

    // 2. Voice frame callback
    override suspend fun onVoiceFrame(payload: VoiceFramePayload) {
        if (_activeSession.value == payload.sessionId) {
            JitterBuffer.push(payload)
        }
    }

    // 3. Voice end callback
    override suspend fun onVoiceEnd(payload: VoiceEndPayload, conversationId: String) {
        if (_activeSession.value != payload.sessionId) return

        receiveScope.launch {
            // Wait for remaining frames to play
            delay(JitterBuffer.size() * 20L + 100L)

            playbackJob?.cancel()
            playbackJob = null

            runCatching {
                audioTrack?.stop()
                audioTrack?.release()
            }
            audioTrack = null

            decoder.stop()
            JitterBuffer.clear()
            _activeSession.value = null

            runCatching {
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }

            // Save Voice Message to Database for Receiver
            val text = "[Voice Message] ${payload.durationMs}"
            val message = MessageEntity(
                id = payload.sessionId,
                text = text,
                senderId = receivingSenderUsername,
                senderName = receivingSenderUsername,
                timestamp = System.currentTimeMillis() - payload.durationMs,
                isOutgoing = false,
                status = "DELIVERED",
                hopCount = 0,
                conversationId = conversationId
            )
            withContext(Dispatchers.IO) {
                messageDao.insertMessage(message)
            }

            _sessionFlow.emit(
                VoiceSession(
                    sessionId = payload.sessionId,
                    senderUsername = "",
                    conversationId = conversationId,
                    state = VoiceSessionState.Completed(payload.durationMs),
                    durationMs = payload.durationMs,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = false
                )
            )
        }
    }

    private fun startPlayback() {
        val track = audioTrack ?: return
        track.play()
        playbackJob = receiveScope.launch(audioPlaybackDispatcher) {
            try {
                while (isActive && _activeSession.value != null) {
                    val opusBytes = runBlocking { JitterBuffer.pull() }  // JitterBuffer pull is suspendable
                    val pcm = decoder.decodeFrame(opusBytes)
                    track.write(pcm, 0, pcm.size)
                }
            } catch (e: CancellationException) {
                // Done
            }
        }
    }
}
