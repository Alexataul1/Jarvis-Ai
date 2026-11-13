package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AssistantMode
import com.example.data.model.ChatMessage
import com.example.service.GeminiLiveVoiceEngine
import com.example.service.LiveAudioBridge
import com.example.service.LiveConnectionState
import com.example.service.LiveErrorCategory
import com.example.service.LiveVoiceEvent
import com.example.service.LiveVoiceSessionHandle
import com.example.service.LiveVoiceTransport
import com.example.service.VoicePersona
import com.example.service.computePcm16Rms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveVoiceEngineTest {

    private class FakeSessionHandle : LiveVoiceSessionHandle {
        val incomingEvents = Channel<LiveVoiceEvent>(Channel.UNLIMITED)
        val sentAudioChunks = mutableListOf<ByteArray>()
        val sentTextMessages = mutableListOf<String>()
        var closed = false

        override fun receive(): Flow<LiveVoiceEvent> = incomingEvents.receiveAsFlow()

        override suspend fun sendAudioChunk(pcm16kBytes: ByteArray) {
            sentAudioChunks.add(pcm16kBytes)
        }

        override suspend fun sendTextMessage(text: String) {
            sentTextMessages.add(text)
        }

        override suspend fun sendMultimodalMessage(bitmap: Bitmap, prompt: String) {
            sentTextMessages.add("image:$prompt")
        }

        override suspend fun close() {
            closed = true
            incomingEvents.close()
        }
    }

    private class FakeAudioBridge : LiveAudioBridge {
        var micChannel = Channel<Pair<ByteArray, Float>>(Channel.UNLIMITED)
        var playbackStartedCount = 0
        var interruptPlaybackCount = 0
        var stopRecordingCount = 0
        var releaseCount = 0
        val writtenPlaybackAudio = mutableListOf<ByteArray>()

        override fun startRecordingFlow(): Flow<Pair<ByteArray, Float>> {
            micChannel = Channel(Channel.UNLIMITED)
            return micChannel.receiveAsFlow()
        }

        override fun startPlayback() {
            playbackStartedCount++
        }

        override fun writePlaybackAudio(pcm24kBytes: ByteArray): Float {
            writtenPlaybackAudio.add(pcm24kBytes)
            return 0.45f
        }

        override fun interruptPlayback() {
            interruptPlaybackCount++
        }

        override fun stopRecording() {
            stopRecordingCount++
            micChannel.close()
        }

        override fun release() {
            releaseCount++
            stopRecording()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 2000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            kotlinx.coroutines.delay(15)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    @Test
    fun `live connection transitions cleanly through CONNECTING CONNECTED LISTENING SPEAKING and stops on exit`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sessionHandle = FakeSessionHandle()
        val audioBridge = FakeAudioBridge()
        val handshakeGate = CompletableDeferred<Unit>()
        var capturedVoiceName: String? = null
        var capturedModelName: String? = null
        var capturedSystemInstruction: String? = null

        val transport = object : LiveVoiceTransport {
            override suspend fun connect(
                modelName: String,
                voiceName: String,
                systemInstructionText: String,
                resumptionHandle: String?
            ): LiveVoiceSessionHandle {
                capturedModelName = modelName
                capturedVoiceName = voiceName
                capturedSystemInstruction = systemInstructionText
                handshakeGate.await()
                return sessionHandle
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = GeminiLiveVoiceEngine(
            context = app,
            isNetworkOnline = { true },
            transport = transport,
            audioBridge = audioBridge,
            permissionChecker = { true },
            scope = scope
        )

        val committedTurns = mutableListOf<Pair<Boolean, String>>()
        engine.onTurnCommitted = { isUser, text -> committedTurns.add(isUser to text) }

        val history = listOf(
            ChatMessage(sessionId = "s1", text = "Mera naam Kabir hai", isUser = true),
            ChatMessage(sessionId = "s1", text = "Namaste Kabir! Main Jarvis hoon.", isUser = false)
        )

        engine.startSession(AssistantMode.AUTO, VoicePersona.DIYA_HINDI, history)

        // Must enter CONNECTING first and must NEVER show fake "Gemini offline" error
        waitUntil { engine.connectionState.value == LiveConnectionState.CONNECTING }
        assertNull(engine.error.value)

        // Complete WebSocket handshake -> moves to LISTENING and streams mic PCM audio
        handshakeGate.complete(Unit)
        waitUntil { engine.connectionState.value == LiveConnectionState.LISTENING }
        assertEquals(GeminiLiveVoiceEngine.LIVE_MODEL_NAME, capturedModelName)
        assertEquals("Aoede", capturedVoiceName)
        assertTrue(capturedSystemInstruction.orEmpty().contains("Mera naam Kabir hai"))
        assertEquals(1, audioBridge.playbackStartedCount)

        // Stream mic audio continuously
        audioBridge.micChannel.send(byteArrayOf(1, 2, 3, 4) to 0.3f)
        waitUntil { sessionHandle.sentAudioChunks.isNotEmpty() }
        assertEquals(1, sessionHandle.sentAudioChunks.size)

        // User speaks Hindi + AI responds with streaming transcripts & 24kHz audio
        sessionHandle.incomingEvents.send(LiveVoiceEvent.InputTranscript("Aaj ka mausam kaisa hai?"))
        waitUntil { engine.userTranscript.value.contains("mausam") }

        sessionHandle.incomingEvents.send(LiveVoiceEvent.OutputTranscript("Aaj dhoop nikli hai aur "))
        sessionHandle.incomingEvents.send(LiveVoiceEvent.AudioChunk(byteArrayOf(10, 20, 30, 40)))
        waitUntil { engine.connectionState.value == LiveConnectionState.SPEAKING }
        assertEquals(1, audioBridge.writtenPlaybackAudio.size)

        sessionHandle.incomingEvents.send(LiveVoiceEvent.OutputTranscript("mausam suhavna hai."))
        sessionHandle.incomingEvents.send(LiveVoiceEvent.TurnComplete)
        waitUntil {
            engine.connectionState.value == LiveConnectionState.LISTENING &&
                committedTurns.size == 2
        }

        assertEquals(true to "Aaj ka mausam kaisa hai?", committedTurns[0])
        assertEquals(false to "Aaj dhoop nikli hai aur mausam suhavna hai.", committedTurns[1])

        // Cleanly close Live Voice session
        engine.stopSession()
        waitUntil {
            engine.connectionState.value == LiveConnectionState.DISCONNECTED &&
                sessionHandle.closed &&
                audioBridge.releaseCount >= 1
        }
        scope.cancel()
    }

    @Test
    fun `barge-in interruption flushes playback audio immediately and returns to listening`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sessionHandle = FakeSessionHandle()
        val audioBridge = FakeAudioBridge()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val engine = GeminiLiveVoiceEngine(
            context = app,
            isNetworkOnline = { true },
            transport = object : LiveVoiceTransport {
                override suspend fun connect(
                    modelName: String,
                    voiceName: String,
                    systemInstructionText: String,
                    resumptionHandle: String?
                ): LiveVoiceSessionHandle = sessionHandle
            },
            audioBridge = audioBridge,
            permissionChecker = { true },
            scope = scope
        )

        engine.startSession(AssistantMode.ONLINE, VoicePersona.ARJUN_HINDI, emptyList())
        waitUntil { engine.connectionState.value == LiveConnectionState.LISTENING }

        sessionHandle.incomingEvents.send(LiveVoiceEvent.OutputTranscript("Long explanation..."))
        sessionHandle.incomingEvents.send(LiveVoiceEvent.AudioChunk(byteArrayOf(5, 6, 7, 8)))
        waitUntil { engine.connectionState.value == LiveConnectionState.SPEAKING }

        // Server signals user barge-in interruption
        sessionHandle.incomingEvents.send(LiveVoiceEvent.Interrupted)
        waitUntil {
            engine.connectionState.value == LiveConnectionState.LISTENING &&
                audioBridge.interruptPlaybackCount == 1
        }
        assertEquals("", engine.aiTranscript.value)

        // Mute pauses mic stream without dropping WebSocket session; unmute resumes it
        engine.setMicMuted(true)
        waitUntil {
            engine.isMicMuted.value &&
                engine.connectionState.value == LiveConnectionState.CONNECTED &&
                audioBridge.stopRecordingCount >= 1
        }
        assertFalse(sessionHandle.closed)

        engine.setMicMuted(false)
        waitUntil { !engine.isMicMuted.value && engine.connectionState.value == LiveConnectionState.LISTENING }
        audioBridge.micChannel.send(byteArrayOf(9, 10) to 0.25f)
        waitUntil { sessionHandle.sentAudioChunks.isNotEmpty() }

        engine.release()
        scope.cancel()
    }

    @Test
    fun `offline mode, missing internet, and permission checks never call WebSocket and keep clean separation`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var transportCalls = 0
        val audioBridge = FakeAudioBridge()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val transport = object : LiveVoiceTransport {
            override suspend fun connect(
                modelName: String,
                voiceName: String,
                systemInstructionText: String,
                resumptionHandle: String?
            ): LiveVoiceSessionHandle {
                transportCalls++
                return FakeSessionHandle()
            }
        }

        // 1. Offline mode active even with internet available -> does not connect cloud Live
        val offlineModeEngine = GeminiLiveVoiceEngine(
            context = app,
            isNetworkOnline = { true },
            transport = transport,
            audioBridge = audioBridge,
            permissionChecker = { true },
            scope = scope
        )
        offlineModeEngine.startSessionInternal(AssistantMode.OFFLINE, VoicePersona.DIYA_HINDI, emptyList())
        assertEquals(0, transportCalls)
        assertEquals(LiveConnectionState.DISCONNECTED, offlineModeEngine.connectionState.value)
        assertEquals(LiveErrorCategory.OFFLINE_MODE_ACTIVE, offlineModeEngine.error.value?.category)
        assertFalse(offlineModeEngine.error.value?.message.orEmpty().contains("Gemini offline", ignoreCase = true))

        // 2. Auto mode with no internet -> explicit NO_INTERNET notice
        val noInternetEngine = GeminiLiveVoiceEngine(
            context = app,
            isNetworkOnline = { false },
            transport = transport,
            audioBridge = audioBridge,
            permissionChecker = { true },
            scope = scope
        )
        noInternetEngine.startSessionInternal(AssistantMode.AUTO, VoicePersona.DIYA_HINDI, emptyList())
        assertEquals(0, transportCalls)
        assertEquals(LiveConnectionState.DISCONNECTED, noInternetEngine.connectionState.value)
        assertEquals(LiveErrorCategory.NO_INTERNET, noInternetEngine.error.value?.category)

        // 3. Missing RECORD_AUDIO permission -> MISSING_PERMISSION
        val noPermEngine = GeminiLiveVoiceEngine(
            context = app,
            isNetworkOnline = { true },
            transport = transport,
            audioBridge = audioBridge,
            permissionChecker = { false },
            scope = scope
        )
        noPermEngine.startSessionInternal(AssistantMode.ONLINE, VoicePersona.DIYA_HINDI, emptyList())
        assertEquals(0, transportCalls)
        assertEquals(LiveConnectionState.ERROR, noPermEngine.connectionState.value)
        assertEquals(LiveErrorCategory.MISSING_PERMISSION, noPermEngine.error.value?.category)

        scope.cancel()
    }

    @Test
    fun `mapLiveException classifies auth, unsupported model, quota, timeout, and audio failures accurately`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine = GeminiLiveVoiceEngine(context = app, isNetworkOnline = { true })

        val authErr = engine.mapLiveException(RuntimeException("HTTP 403 App Check token rejected"))
        assertEquals(LiveErrorCategory.AUTHENTICATION, authErr.category)
        assertEquals(403, authErr.httpStatus)

        val modelErr = engine.mapLiveException(RuntimeException("404 model gemini-live not found"))
        assertEquals(LiveErrorCategory.UNSUPPORTED_MODEL, modelErr.category)
        assertEquals(404, modelErr.httpStatus)

        val quotaErr = engine.mapLiveException(RuntimeException("429 RESOURCE_EXHAUSTED"))
        assertEquals(LiveErrorCategory.QUOTA_EXCEEDED, quotaErr.category)
        assertEquals(429, quotaErr.httpStatus)

        val timeoutErr = engine.mapLiveException(SocketTimeoutException("WebSocket handshake timed out"))
        assertEquals(LiveErrorCategory.SERVER_OR_TIMEOUT, timeoutErr.category)

        val netErr = engine.mapLiveException(UnknownHostException("Unable to resolve host generativelanguage.googleapis.com"))
        assertEquals(LiveErrorCategory.NO_INTERNET, netErr.category)

        val audioErr = engine.mapLiveException(IllegalStateException("Microphone AudioRecord failed to initialize"))
        assertEquals(LiveErrorCategory.AUDIO_HARDWARE, audioErr.category)

        // Ensure computePcm16Rms returns bounded float values cleanly
        assertEquals(0f, computePcm16Rms(byteArrayOf()), 0.001f)
        val nonZeroRms = computePcm16Rms(byteArrayOf(0, 16, 0, -16))
        assertTrue(nonZeroRms in 0.05f..1f)
        assertNotNull(engine.buildSystemInstruction(emptyList()))
    }
}
