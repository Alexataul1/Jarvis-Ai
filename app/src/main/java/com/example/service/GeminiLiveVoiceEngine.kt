package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.AssistantMode
import com.example.data.model.ChatMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.APINotConfiguredException
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerSetupComplete
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ServiceConnectionHandshakeFailedException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.activityDetectionConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.realtimeInputConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.sqrt

private const val TAG = "GeminiLiveVoiceEngine"
private const val INPUT_SAMPLE_RATE = 16000
private const val OUTPUT_SAMPLE_RATE = 24000

enum class LiveConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    SPEAKING,
    ERROR
}

enum class LiveErrorCategory {
    NO_INTERNET,
    OFFLINE_MODE_ACTIVE,
    MISSING_PERMISSION,
    AUTHENTICATION,
    UNSUPPORTED_MODEL,
    QUOTA_EXCEEDED,
    AUDIO_HARDWARE,
    SERVER_OR_TIMEOUT,
    UNKNOWN
}

data class LiveVoiceError(
    val category: LiveErrorCategory,
    val message: String,
    val httpStatus: Int? = null
)

sealed interface LiveVoiceEvent {
    data object SetupComplete : LiveVoiceEvent
    data class AudioChunk(val pcmBytes: ByteArray) : LiveVoiceEvent
    data class InputTranscript(val text: String) : LiveVoiceEvent
    data class OutputTranscript(val text: String) : LiveVoiceEvent
    data class TextChunk(val text: String) : LiveVoiceEvent
    data object Interrupted : LiveVoiceEvent
    data object TurnComplete : LiveVoiceEvent
    data class GoAway(val timeLeftSeconds: Long?) : LiveVoiceEvent
    data class ResumptionUpdate(val handle: String?, val resumable: Boolean) : LiveVoiceEvent
}

interface LiveVoiceSessionHandle {
    fun receive(): Flow<LiveVoiceEvent>
    suspend fun sendAudioChunk(pcm16kBytes: ByteArray)
    suspend fun sendTextMessage(text: String)
    suspend fun sendMultimodalMessage(bitmap: Bitmap, prompt: String)
    suspend fun close()
}

interface LiveVoiceTransport {
    suspend fun connect(
        modelName: String,
        voiceName: String,
        systemInstructionText: String,
        resumptionHandle: String?
    ): LiveVoiceSessionHandle
}

interface LiveAudioBridge {
    fun startRecordingFlow(): Flow<Pair<ByteArray, Float>>
    fun startPlayback()
    fun writePlaybackAudio(pcm24kBytes: ByteArray): Float
    fun interruptPlayback()
    fun stopRecording()
    fun release()
}

@OptIn(PublicPreviewAPI::class)
class FirebaseLiveVoiceTransport : LiveVoiceTransport {
    override suspend fun connect(
        modelName: String,
        voiceName: String,
        systemInstructionText: String,
        resumptionHandle: String?
    ): LiveVoiceSessionHandle {
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = modelName,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice(voiceName))
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
                realtimeInputConfig = realtimeInputConfig {
                    activityHandling = RealtimeInputConfig.ActivityHandling.INTERRUPT
                    automaticActivityDetection = activityDetectionConfig {
                        startSensitivity = ActivityDetectionConfig.Sensitivity.HIGH
                        endSensitivity = ActivityDetectionConfig.Sensitivity.HIGH
                        silenceDurationMs = 600
                    }
                }
            },
            systemInstruction = content { text(systemInstructionText) }
        )

        val session: LiveSession = withTimeout(15_000L) {
            if (!resumptionHandle.isNullOrBlank()) {
                model.connect(SessionResumptionConfig(resumptionHandle))
            } else {
                model.connect()
            }
        }
        return FirebaseLiveVoiceSessionHandle(session)
    }

    private class FirebaseLiveVoiceSessionHandle(
        private val session: LiveSession
    ) : LiveVoiceSessionHandle {
        override fun receive(): Flow<LiveVoiceEvent> = flow {
            session.receive().collect { msg ->
                when (msg) {
                    is LiveServerSetupComplete -> emit(LiveVoiceEvent.SetupComplete)
                    is LiveServerGoAway -> emit(LiveVoiceEvent.GoAway(msg.timeLeft?.inWholeSeconds))
                    is LiveSessionResumptionUpdate -> emit(
                        LiveVoiceEvent.ResumptionUpdate(msg.newHandle, msg.resumable ?: false)
                    )
                    is LiveServerContent -> {
                        if (msg.interrupted) {
                            emit(LiveVoiceEvent.Interrupted)
                        }
                        val inText = msg.inputTranscription?.text.orEmpty()
                        if (inText.isNotBlank()) {
                            emit(LiveVoiceEvent.InputTranscript(inText))
                        }
                        val outText = msg.outputTranscription?.text.orEmpty()
                        if (outText.isNotBlank()) {
                            emit(LiveVoiceEvent.OutputTranscript(outText))
                        }
                        val parts = msg.content?.parts.orEmpty()
                        for (part in parts) {
                            when (part) {
                                is InlineDataPart -> {
                                    if (part.mimeType.startsWith("audio/", ignoreCase = true) &&
                                        part.inlineData.isNotEmpty()
                                    ) {
                                        emit(LiveVoiceEvent.AudioChunk(part.inlineData))
                                    }
                                }
                                is TextPart -> {
                                    if (!part.isThought && part.text.isNotBlank()) {
                                        emit(LiveVoiceEvent.TextChunk(part.text))
                                    }
                                }
                            }
                        }
                        if (msg.turnComplete || msg.generationComplete) {
                            emit(LiveVoiceEvent.TurnComplete)
                        }
                    }
                }
            }
        }

        override suspend fun sendAudioChunk(pcm16kBytes: ByteArray) {
            session.sendAudioRealtime(InlineData(pcm16kBytes, "audio/pcm"))
        }

        override suspend fun sendTextMessage(text: String) {
            session.send(text, turnComplete = true)
        }

        override suspend fun sendMultimodalMessage(bitmap: Bitmap, prompt: String) {
            val contentMsg = content(role = "user") {
                image(bitmap)
                text(prompt.ifBlank { "Describe this image clearly and concisely." })
            }
            session.send(contentMsg, turnComplete = true)
        }

        override suspend fun close() {
            session.close()
        }
    }
}

class AndroidLiveAudioBridge : LiveAudioBridge {
    private val trackLock = Any()
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioTrack: AudioTrack? = null

    @SuppressLint("MissingPermission")
    override fun startRecordingFlow(): Flow<Pair<ByteArray, Float>> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            throw IllegalStateException("AudioRecord buffer size invalid ($minBuf).")
        }

        val chunkSizeBytes = 1280 // 40ms @ 16kHz 16-bit mono
        val bufSize = maxOf(minBuf * 2, chunkSizeBytes * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone AudioRecord failed to initialize (state=${rec.state}).")
        }

        recorder = rec
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hardware audio FX setup skipped", e)
        }

        rec.startRecording()
        val buffer = ByteArray(chunkSizeBytes)
        try {
            while (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val copy = buffer.copyOf(read)
                    val rms = computePcm16Rms(copy)
                    emit(copy to rms)
                }
            }
        } finally {
            stopRecording()
        }
    }.flowOn(Dispatchers.IO)

    override fun startPlayback() {
        synchronized(trackLock) {
            if (audioTrack != null) return
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                throw IllegalStateException("AudioTrack buffer size invalid ($minBuf).")
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw IllegalStateException("Speaker AudioTrack failed to initialize.")
            }
            track.play()
            audioTrack = track
        }
    }

    override fun writePlaybackAudio(pcm24kBytes: ByteArray): Float {
        if (pcm24kBytes.isEmpty()) return 0f
        synchronized(trackLock) {
            if (audioTrack == null) startPlayback()
            val track = audioTrack ?: return 0f
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(pcm24kBytes, 0, pcm24kBytes.size)
        }
        return computePcm16Rms(pcm24kBytes)
    }

    override fun interruptPlayback() {
        synchronized(trackLock) {
            val track = audioTrack ?: return
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                track.play()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack flush warning", e)
            }
        }
    }

    override fun stopRecording() {
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        try {
            recorder?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }

    override fun release() {
        stopRecording()
        synchronized(trackLock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
            } catch (_: Exception) {
            }
            try {
                audioTrack?.release()
            } catch (_: Exception) {
            }
            audioTrack = null
        }
    }
}

internal fun computePcm16Rms(pcmBytes: ByteArray): Float {
    val samples = pcmBytes.size / 2
    if (samples <= 0) return 0f
    var sum = 0.0
    var i = 0
    while (i + 1 < pcmBytes.size) {
        val sample = ((pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)).toShort()
        sum += (sample * sample).toDouble()
        i += 2
    }
    val rms = sqrt(sum / samples).toFloat()
    return (rms / 7000f).coerceIn(0.05f, 1f)
}

/**
 * Official Gemini Live API bidirectional audio engine for JARVIS.
 * Streams 16 kHz PCM mic audio continuously over Firebase AI Logic's LiveSession WebSocket,
 * plays 24 kHz PCM voice responses via AudioTrack, supports hardware+server interruption,
 * and exposes honest connection states (Connecting, Connected, Listening, Speaking, Disconnected).
 */
class GeminiLiveVoiceEngine(
    private val context: Context,
    private val isNetworkOnline: () -> Boolean,
    private val transport: LiveVoiceTransport = FirebaseLiveVoiceTransport(),
    private val audioBridge: LiveAudioBridge = AndroidLiveAudioBridge(),
    private val permissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        const val LIVE_MODEL_NAME = "gemini-3.1-flash-live-preview"
    }

    private val sessionMutex = Mutex()
    private var activeSession: LiveVoiceSessionHandle? = null
    private var connectJob: Job? = null
    private var receiveJob: Job? = null
    private var micJob: Job? = null
    private var resumptionHandle: String? = null
    private var isClosingExplicitly = false
    private var lastPersona: VoicePersona = VoicePersona.DIYA_HINDI
    private var lastHistory: List<ChatMessage> = emptyList()

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _error = MutableStateFlow<LiveVoiceError?>(null)
    val error: StateFlow<LiveVoiceError?> = _error.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

    private val _aiTranscript = MutableStateFlow("")
    val aiTranscript: StateFlow<String> = _aiTranscript.asStateFlow()

    var onTurnCommitted: ((isUser: Boolean, text: String) -> Unit)? = null

    fun startSession(
        assistantMode: AssistantMode,
        persona: VoicePersona,
        recentHistory: List<ChatMessage>
    ) {
        lastPersona = persona
        lastHistory = recentHistory
        connectJob?.cancel()
        connectJob = scope.launch {
            startSessionInternal(assistantMode, persona, recentHistory, resumeHandle = null)
        }
    }

    fun reconnect(
        assistantMode: AssistantMode,
        persona: VoicePersona = lastPersona,
        recentHistory: List<ChatMessage> = lastHistory
    ) {
        lastPersona = persona
        lastHistory = recentHistory
        connectJob?.cancel()
        connectJob = scope.launch {
            startSessionInternal(assistantMode, persona, recentHistory, resumeHandle = resumptionHandle)
        }
    }

    suspend fun startSessionInternal(
        assistantMode: AssistantMode,
        persona: VoicePersona,
        recentHistory: List<ChatMessage>,
        resumeHandle: String? = null
    ) {
        sessionMutex.withLock {
            cleanupSessionLocked(nextState = LiveConnectionState.DISCONNECTED)
            isClosingExplicitly = false
            _error.value = null
            _userTranscript.value = ""
            _aiTranscript.value = ""

            if (assistantMode == AssistantMode.OFFLINE) {
                _error.value = LiveVoiceError(
                    category = LiveErrorCategory.OFFLINE_MODE_ACTIVE,
                    message = "Offline Mode is selected. Live Voice requires an online Gemini connection; switch to Auto or Online to connect."
                )
                _connectionState.value = LiveConnectionState.DISCONNECTED
                return
            }

            if (!isNetworkOnline()) {
                _error.value = LiveVoiceError(
                    category = LiveErrorCategory.NO_INTERNET,
                    message = "No internet connection. Live Voice requires internet; Offline Llama 3.2 remains available in Chat."
                )
                _connectionState.value = LiveConnectionState.DISCONNECTED
                return
            }

            if (!permissionChecker()) {
                _error.value = LiveVoiceError(
                    category = LiveErrorCategory.MISSING_PERMISSION,
                    message = "Microphone permission is required for Live Voice."
                )
                _connectionState.value = LiveConnectionState.ERROR
                return
            }

            _connectionState.value = LiveConnectionState.CONNECTING
            try {
                audioBridge.startPlayback()
                val session = transport.connect(
                    modelName = LIVE_MODEL_NAME,
                    voiceName = persona.geminiLiveVoiceName,
                    systemInstructionText = buildSystemInstruction(recentHistory),
                    resumptionHandle = resumeHandle
                )
                activeSession = session
                _connectionState.value = LiveConnectionState.CONNECTED

                startReceiveLoop(session, assistantMode, persona, recentHistory)
                if (!_isMicMuted.value) {
                    startMicStreamingLoop(session)
                }
            } catch (e: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    cleanupSessionLocked(nextState = LiveConnectionState.DISCONNECTED)
                }
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to establish Gemini Live session", t)
                cleanupSessionLocked(nextState = LiveConnectionState.ERROR)
                _error.value = mapLiveException(t)
            }
        }
    }

    private fun startReceiveLoop(
        session: LiveVoiceSessionHandle,
        assistantMode: AssistantMode,
        persona: VoicePersona,
        recentHistory: List<ChatMessage>
    ) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val userBuf = StringBuilder()
            val aiBuf = StringBuilder()

            fun flushUserTranscriptIfReady() {
                val u = userBuf.toString().trim()
                if (u.isNotEmpty()) {
                    userBuf.clear()
                    onTurnCommitted?.invoke(true, u)
                }
            }

            fun flushAiTranscriptIfReady() {
                val a = aiBuf.toString().trim()
                if (a.isNotEmpty()) {
                    aiBuf.clear()
                    onTurnCommitted?.invoke(false, a)
                }
            }

            try {
                session.receive().collect { event ->
                    when (event) {
                        is LiveVoiceEvent.SetupComplete -> {
                            if (_connectionState.value == LiveConnectionState.CONNECTING) {
                                _connectionState.value = if (_isMicMuted.value) {
                                    LiveConnectionState.CONNECTED
                                } else {
                                    LiveConnectionState.LISTENING
                                }
                            }
                        }

                        is LiveVoiceEvent.ResumptionUpdate -> {
                            if (event.resumable && !event.handle.isNullOrBlank()) {
                                resumptionHandle = event.handle
                            }
                        }

                        is LiveVoiceEvent.InputTranscript -> {
                            userBuf.append(event.text)
                            _userTranscript.value = userBuf.toString().trim()
                        }

                        is LiveVoiceEvent.OutputTranscript -> {
                            flushUserTranscriptIfReady()
                            aiBuf.append(event.text)
                            _aiTranscript.value = aiBuf.toString().trim()
                        }

                        is LiveVoiceEvent.TextChunk -> {
                            flushUserTranscriptIfReady()
                            aiBuf.append(event.text)
                            _aiTranscript.value = aiBuf.toString().trim()
                        }

                        is LiveVoiceEvent.AudioChunk -> {
                            flushUserTranscriptIfReady()
                            _connectionState.value = LiveConnectionState.SPEAKING
                            _rmsLevel.value = audioBridge.writePlaybackAudio(event.pcmBytes)
                        }

                        is LiveVoiceEvent.Interrupted -> {
                            audioBridge.interruptPlayback()
                            aiBuf.clear()
                            _aiTranscript.value = ""
                            _rmsLevel.value = 0f
                            _connectionState.value = if (_isMicMuted.value) {
                                LiveConnectionState.CONNECTED
                            } else {
                                LiveConnectionState.LISTENING
                            }
                        }

                        is LiveVoiceEvent.TurnComplete -> {
                            flushUserTranscriptIfReady()
                            flushAiTranscriptIfReady()
                            _rmsLevel.value = 0f
                            if (_connectionState.value == LiveConnectionState.SPEAKING ||
                                _connectionState.value == LiveConnectionState.CONNECTED
                            ) {
                                _connectionState.value = if (_isMicMuted.value) {
                                    LiveConnectionState.CONNECTED
                                } else {
                                    LiveConnectionState.LISTENING
                                }
                            }
                        }

                        is LiveVoiceEvent.GoAway -> {
                            Log.w(TAG, "Gemini Live GoAway received (${event.timeLeftSeconds}s left); preparing clean reconnect")
                            if (!isClosingExplicitly && resumptionHandle != null) {
                                launch {
                                    delay(250)
                                    startSessionInternal(assistantMode, persona, recentHistory, resumptionHandle)
                                }
                            }
                        }
                    }
                }
                if (!isClosingExplicitly) {
                    _connectionState.value = LiveConnectionState.DISCONNECTED
                    _rmsLevel.value = 0f
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!isClosingExplicitly) {
                    Log.e(TAG, "Gemini Live receive stream ended with error", t)
                    _error.value = mapLiveException(t)
                    _connectionState.value = LiveConnectionState.ERROR
                    _rmsLevel.value = 0f
                }
            }
        }
    }

    private fun startMicStreamingLoop(session: LiveVoiceSessionHandle) {
        micJob?.cancel()
        if (_connectionState.value == LiveConnectionState.CONNECTED) {
            _connectionState.value = LiveConnectionState.LISTENING
        }
        micJob = scope.launch {
            try {
                audioBridge.startRecordingFlow().collect { (bytes, rms) ->
                    if (!isActive || _isMicMuted.value) return@collect
                    if (_connectionState.value != LiveConnectionState.SPEAKING) {
                        _connectionState.value = LiveConnectionState.LISTENING
                        _rmsLevel.value = rms
                    }
                    session.sendAudioChunk(bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!isClosingExplicitly && !_isMicMuted.value) {
                    Log.e(TAG, "Mic streaming failed", t)
                    _error.value = LiveVoiceError(
                        category = LiveErrorCategory.AUDIO_HARDWARE,
                        message = t.message ?: "Microphone capture failed on this device."
                    )
                    _connectionState.value = LiveConnectionState.ERROR
                }
            }
        }
    }

    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
        val session = activeSession
        if (muted) {
            micJob?.cancel()
            micJob = null
            audioBridge.stopRecording()
            if (_connectionState.value == LiveConnectionState.LISTENING) {
                _connectionState.value = LiveConnectionState.CONNECTED
                _rmsLevel.value = 0f
            }
        } else if (session != null && _connectionState.value != LiveConnectionState.ERROR &&
            _connectionState.value != LiveConnectionState.DISCONNECTED
        ) {
            startMicStreamingLoop(session)
        }
    }

    fun interruptSpeaking() {
        audioBridge.interruptPlayback()
        _rmsLevel.value = 0f
        if (_connectionState.value == LiveConnectionState.SPEAKING) {
            _connectionState.value = if (_isMicMuted.value) {
                LiveConnectionState.CONNECTED
            } else {
                LiveConnectionState.LISTENING
            }
        }
    }

    fun sendTypedMessage(prompt: String, bitmap: Bitmap? = null): Boolean {
        val session = activeSession ?: return false
        val state = _connectionState.value
        if (state == LiveConnectionState.DISCONNECTED || state == LiveConnectionState.ERROR) {
            return false
        }
        interruptSpeaking()
        _aiTranscript.value = ""
        scope.launch {
            try {
                if (bitmap != null) {
                    session.sendMultimodalMessage(bitmap, prompt)
                } else {
                    session.sendTextMessage(prompt)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed sending text in Live session", t)
                _error.value = mapLiveException(t)
                _connectionState.value = LiveConnectionState.ERROR
            }
        }
        return true
    }

    fun stopSession() {
        isClosingExplicitly = true
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            sessionMutex.withLock {
                cleanupSessionLocked(nextState = LiveConnectionState.DISCONNECTED)
            }
        }
    }

    fun stopForOfflineMode() {
        isClosingExplicitly = true
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            sessionMutex.withLock {
                cleanupSessionLocked(nextState = LiveConnectionState.DISCONNECTED)
                _error.value = LiveVoiceError(
                    category = LiveErrorCategory.OFFLINE_MODE_ACTIVE,
                    message = "Offline Mode is selected. Live Voice requires an online Gemini connection; switch to Auto or Online to connect."
                )
            }
        }
    }

    fun release() {
        isClosingExplicitly = true
        connectJob?.cancel()
        connectJob = null
        micJob?.cancel()
        receiveJob?.cancel()
        audioBridge.release()
        scope.launch {
            try {
                activeSession?.close()
            } catch (_: Exception) {
            }
            activeSession = null
        }
        _connectionState.value = LiveConnectionState.DISCONNECTED
    }

    private suspend fun cleanupSessionLocked(nextState: LiveConnectionState) {
        micJob?.cancel()
        micJob = null
        receiveJob?.cancel()
        receiveJob = null
        audioBridge.release()
        try {
            activeSession?.close()
        } catch (_: Exception) {
        }
        activeSession = null
        _rmsLevel.value = 0f
        _connectionState.value = nextState
    }

    internal fun buildSystemInstruction(history: List<ChatMessage>): String {
        val recent = history
            .filter { !it.isError && it.text.isNotBlank() && it.imageUri == null }
            .takeLast(6)
            .joinToString("\n") { turn ->
                val role = if (turn.isUser) "User" else "Jarvis"
                "$role: ${turn.text.trim()}"
            }

        val base = """
            You are Jarvis, an expressive, warm, and natural real-time AI voice assistant.
            You understand and speak Hindi (natural conversational Hindi, avoiding robotic anglicized phrasing), English, and Hinglish seamlessly, matching whichever language the user speaks.
            Keep voice replies clear, concise, and natural (1-3 sentences unless detailed help is asked), without markdown symbols or emojis.
        """.trimIndent()

        return if (recent.isBlank()) base else "$base\n\nRecent conversation context:\n$recent"
    }

    internal fun mapLiveException(t: Throwable): LiveVoiceError {
        val msg = (t.message ?: t.cause?.message).orEmpty()
        val httpCode = listOf(429, 401, 403, 404, 500, 502, 503, 504)
            .firstOrNull { code -> Regex("""\b$code\b""").containsMatchIn(msg) }

        return when {
            msg.contains("AudioRecord", ignoreCase = true) ||
                msg.contains("AudioTrack", ignoreCase = true) ||
                msg.contains("microphone", ignoreCase = true) ->
                LiveVoiceError(
                    category = LiveErrorCategory.AUDIO_HARDWARE,
                    message = "Microphone or speaker audio hardware failed to initialize ($msg)."
                )

            t is InvalidAPIKeyException || t is ServiceDisabledException || t is APINotConfiguredException ||
                httpCode == 401 || httpCode == 403 ||
                msg.contains("App Check", ignoreCase = true) ||
                msg.contains("API_KEY", ignoreCase = true) ||
                msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                LiveVoiceError(
                    category = LiveErrorCategory.AUTHENTICATION,
                    httpStatus = httpCode ?: 403,
                    message = "Gemini Live authentication or App Check verification failed (HTTP ${httpCode ?: 403}). Verify Firebase AI Logic & App Check configuration."
                )

            httpCode == 404 ||
                msg.contains("NOT_FOUND", ignoreCase = true) ||
                (msg.contains("model", ignoreCase = true) &&
                    (msg.contains("not found", ignoreCase = true) || msg.contains("not supported", ignoreCase = true))) ->
                LiveVoiceError(
                    category = LiveErrorCategory.UNSUPPORTED_MODEL,
                    httpStatus = 404,
                    message = "Gemini Live model '$LIVE_MODEL_NAME' is unavailable or unsupported on this backend."
                )

            t is QuotaExceededException || httpCode == 429 ||
                msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                msg.contains("quota", ignoreCase = true) ->
                LiveVoiceError(
                    category = LiveErrorCategory.QUOTA_EXCEEDED,
                    httpStatus = httpCode ?: 429,
                    message = "Gemini Live quota limit reached (HTTP 429). Wait a moment and tap Reconnect."
                )

            t is UnknownHostException || t is ConnectException ||
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                (!isNetworkOnline() && t is IOException) ->
                LiveVoiceError(
                    category = LiveErrorCategory.NO_INTERNET,
                    message = "Network unreachable while connecting to Gemini Live. Check your internet connection and tap Reconnect."
                )

            t is ServiceConnectionHandshakeFailedException || t is ServerException ||
                t is RequestTimeoutException || t is TimeoutCancellationException ||
                t is SocketTimeoutException || t is IOException ||
                httpCode in listOf(500, 502, 503, 504) ->
                LiveVoiceError(
                    category = LiveErrorCategory.SERVER_OR_TIMEOUT,
                    httpStatus = httpCode,
                    message = "Gemini Live WebSocket connection failed (${msg.ifBlank { "server/timeout" }}). Tap Reconnect."
                )

            else ->
                LiveVoiceError(
                    category = LiveErrorCategory.UNKNOWN,
                    httpStatus = httpCode,
                    message = "Gemini Live error: ${msg.ifBlank { "Unexpected connection failure." }}"
                )
        }
    }
}
