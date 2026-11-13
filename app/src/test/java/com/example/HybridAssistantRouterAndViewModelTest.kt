package com.example

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AssistantMode
import com.example.service.HybridAssistantRouter
import com.example.service.LocalLlamaException
import com.example.service.LocalLlamaFailureReason
import com.example.service.OnlineErrorCategory
import com.example.service.OnlineGeminiEngine
import com.example.service.OnlineGeminiException
import com.example.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridAssistantRouterAndViewModelTest {

    private fun setDeviceOnline(app: Application, online: Boolean) {
        val cm = app.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (online && network != null) {
            val caps = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(cm).setNetworkCapabilities(network, caps)
        } else {
            Shadows.shadowOf(cm).setDefaultNetworkActive(online)
        }
    }

    @Test
    fun `ONLINE mode never silently falls back to local Llama on Gemini failure`() = runBlocking {
        var llamaCalled = false
        val router = HybridAssistantRouter(
            onlineStreamFn = { _, _, _ ->
                flow {
                    throw OnlineGeminiException(
                        category = OnlineErrorCategory.QUOTA_EXCEEDED,
                        httpStatus = 429,
                        userMessage = "Gemini rate/quota limit reached (HTTP 429).",
                        retryable = true
                    )
                }
            },
            imageAnalyzeFn = { _, _ -> Result.success("") },
            llamaReadyFn = { true },
            llamaStreamFn = { _, _ ->
                llamaCalled = true
                flow { emit("Should not be called in ONLINE mode") }
            },
            deviceActionFn = { null },
            mapOnlineErrorFn = { t -> t as? OnlineGeminiException ?: OnlineGeminiEngine().mapException(t) }
        )

        val reply = router.routeMessage(
            prompt = "Hello Gemini",
            history = emptyList(),
            mode = AssistantMode.ONLINE,
            isOnline = true,
            isVoice = false,
            onStreamChunk = { _, _, _ -> }
        )

        assertFalse(llamaCalled)
        assertTrue(reply.isError)
        assertTrue(reply.text.contains("HTTP 429"))
        assertFalse(reply.isOfflineEngine)
    }

    @Test
    fun `OFFLINE mode never calls cloud Gemini and reports missing model or text-only image limitation honestly`() = runBlocking {
        var onlineCalled = false
        val routerWithoutModel = HybridAssistantRouter(
            onlineStreamFn = { _, _, _ ->
                onlineCalled = true
                flow { emit("Should not be called") }
            },
            imageAnalyzeFn = { _, _ ->
                onlineCalled = true
                Result.success("Nope")
            },
            llamaReadyFn = { false },
            llamaStreamFn = { _, _ ->
                flow {
                    throw LocalLlamaException(
                        LocalLlamaFailureReason.MODEL_NOT_INSTALLED,
                        "Meta Llama 3.2 1B Instruct is not downloaded."
                    )
                }
            },
            deviceActionFn = { null },
            mapOnlineErrorFn = { OnlineGeminiEngine().mapException(it) }
        )

        val textReply = routerWithoutModel.routeMessage(
            prompt = "Explain gravity",
            history = emptyList(),
            mode = AssistantMode.OFFLINE,
            isOnline = true, // even if network happens to be on, OFFLINE mode never touches cloud!
            isVoice = false,
            onStreamChunk = { _, _, _ -> }
        )

        assertFalse(onlineCalled)
        assertTrue(textReply.isError)
        assertTrue(textReply.text.contains("not downloaded"))

        val imageReply = routerWithoutModel.routeMessage(
            prompt = "What is in this photo?",
            history = emptyList(),
            mode = AssistantMode.OFFLINE,
            isOnline = true,
            isVoice = false,
            hasImageAttachment = true,
            onStreamChunk = { _, _, _ -> }
        )
        assertFalse(onlineCalled)
        assertTrue(imageReply.isError)
        assertTrue(imageReply.text.contains("text-only", ignoreCase = true))
    }

    @Test
    fun `AUTO mode falls back to installed Llama when Gemini fails and reports error if neither works`() = runBlocking {
        val routerWithFallback = HybridAssistantRouter(
            onlineStreamFn = { _, _, _ ->
                flow { throw java.net.UnknownHostException("offline") }
            },
            imageAnalyzeFn = { _, _ -> Result.success("") },
            llamaReadyFn = { true },
            llamaStreamFn = { _, _ ->
                flow {
                    emit("लोकल लामा ")
                    emit("उत्तर")
                }
            },
            deviceActionFn = { null },
            mapOnlineErrorFn = { OnlineGeminiEngine().mapException(it) }
        )

        val fallbackReply = routerWithFallback.routeMessage(
            prompt = "Namaste",
            history = emptyList(),
            mode = AssistantMode.AUTO,
            isOnline = true,
            isVoice = false,
            onStreamChunk = { _, _, _ -> }
        )
        assertFalse(fallbackReply.isError)
        assertTrue(fallbackReply.isOfflineEngine)
        assertEquals("लोकल लामा उत्तर", fallbackReply.text)
        assertEquals("Llama 3.2 • Fallback", fallbackReply.engineLabel)

        val routerNoFallback = HybridAssistantRouter(
            onlineStreamFn = { _, _, _ -> flow { throw java.net.UnknownHostException("offline") } },
            imageAnalyzeFn = { _, _ -> Result.success("") },
            llamaReadyFn = { false },
            llamaStreamFn = { _, _ -> flow {} },
            deviceActionFn = { null },
            mapOnlineErrorFn = { OnlineGeminiEngine().mapException(it) }
        )

        val failedBothReply = routerNoFallback.routeMessage(
            prompt = "Namaste",
            history = emptyList(),
            mode = AssistantMode.AUTO,
            isOnline = true,
            isVoice = false,
            onStreamChunk = { _, _, _ -> }
        )
        assertTrue(failedBothReply.isError)
        assertTrue(failedBothReply.text.contains("Llama 3.2 1B"))
    }

    private fun pumpMainLooperUntil(timeoutMs: Long = 3000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            if (condition()) return
            Thread.sleep(15)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `MainViewModel saves messages once resets loading on error and handles stopGeneration cleanly`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        setDeviceOnline(app, true)
        val viewModel = MainViewModel(app)
        viewModel.createNewSession()
        pumpMainLooperUntil { viewModel.currentSessionId.value != "session_main" }

        viewModel.setRouterForTesting(
            HybridAssistantRouter(
                onlineStreamFn = { _, _, _ ->
                    flow {
                        emit("Gemini ")
                        kotlinx.coroutines.delay(40)
                        emit("reply")
                    }
                },
                imageAnalyzeFn = { _, _ -> Result.success("") },
                llamaReadyFn = { false },
                llamaStreamFn = { _, _ -> flow {} },
                deviceActionFn = { null },
                mapOnlineErrorFn = { OnlineGeminiEngine().mapException(it) }
            )
        )

        viewModel.setAssistantMode(AssistantMode.ONLINE)
        viewModel.sendMessage("Hello Jarvis")
        pumpMainLooperUntil { viewModel.currentMessages.value.size >= 2 && !viewModel.isGenerating.value }

        val msgs = viewModel.currentMessages.value
        assertEquals("Expected exactly 1 user + 1 assistant message", 2, msgs.size)
        assertTrue(msgs[0].isUser)
        assertEquals("Hello Jarvis", msgs[0].text)
        assertFalse(msgs[1].isUser)
        assertEquals("Gemini reply", msgs[1].text)
        assertFalse(viewModel.isGenerating.value)
        assertNull(viewModel.streamingMessage.value)

        // Now test cancellation stops generation and clears loading state cleanly
        viewModel.setRouterForTesting(
            HybridAssistantRouter(
                onlineStreamFn = { _, _, _ ->
                    flow {
                        emit("Partial ")
                        kotlinx.coroutines.delay(5000)
                        emit("never")
                    }
                },
                imageAnalyzeFn = { _, _ -> Result.success("") },
                llamaReadyFn = { false },
                llamaStreamFn = { _, _ -> flow {} },
                deviceActionFn = { null },
                mapOnlineErrorFn = { OnlineGeminiEngine().mapException(it) }
            )
        )
        viewModel.sendMessage("Slow query")
        pumpMainLooperUntil { viewModel.isGenerating.value && viewModel.streamingMessage.value?.text?.isNotEmpty() == true }
        viewModel.stopGeneration()
        pumpMainLooperUntil { !viewModel.isGenerating.value && viewModel.streamingMessage.value == null }

        assertFalse(viewModel.isGenerating.value)
        assertNull(viewModel.streamingMessage.value)
    }
}
