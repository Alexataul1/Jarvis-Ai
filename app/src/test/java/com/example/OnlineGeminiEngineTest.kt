package com.example

import com.example.data.model.ChatMessage
import com.example.service.GeminiTransport
import com.example.service.OnlineErrorCategory
import com.example.service.OnlineGeminiEngine
import com.example.service.OnlineGeminiException
import com.google.firebase.ai.type.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineGeminiEngineTest {

    @Test
    fun `retries transient 429 and 503 errors with backoff then succeeds`() = runBlocking {
        var calls = 0
        val transport = object : GeminiTransport {
            override fun generateContentStream(modelId: String, contents: List<Content>): Flow<String> = flow {
                calls++
                when (calls) {
                    1 -> throw IOException("HTTP 429 RESOURCE_EXHAUSTED quota")
                    2 -> throw IOException("HTTP 503 Service Unavailable")
                    else -> {
                        emit("नमस्ते! ")
                        emit("मैं तैयार हूँ।")
                    }
                }
            }

            override suspend fun generateContent(modelId: String, contents: List<Content>): String = ""
        }

        val engine = OnlineGeminiEngine(transport = transport, baseRetryDelayMs = 10L, maxRetries = 2)
        val tokens = engine.generateStream("hello").toList()

        assertEquals(3, calls)
        assertEquals("नमस्ते! मैं तैयार हूँ।", tokens.joinToString(""))
    }

    @Test
    fun `maps 403 auth failure without retrying`() = runBlocking {
        var calls = 0
        val transport = object : GeminiTransport {
            override fun generateContentStream(modelId: String, contents: List<Content>): Flow<String> = flow {
                calls++
                throw IllegalStateException("HTTP 403 PERMISSION_DENIED App Check failed")
            }

            override suspend fun generateContent(modelId: String, contents: List<Content>): String = ""
        }

        val engine = OnlineGeminiEngine(transport = transport, baseRetryDelayMs = 10L, maxRetries = 2)
        var caught: OnlineGeminiException? = null
        try {
            engine.generateStream("test").toList()
        } catch (e: OnlineGeminiException) {
            caught = e
        }

        assertEquals(1, calls)
        assertEquals(OnlineErrorCategory.AUTHENTICATION, caught?.category)
        assertEquals(403, caught?.httpStatus)
    }

    @Test
    fun `never retries CancellationException`() = runBlocking {
        var calls = 0
        val transport = object : GeminiTransport {
            override fun generateContentStream(modelId: String, contents: List<Content>): Flow<String> = flow {
                calls++
                throw CancellationException("User cancelled stream")
            }

            override suspend fun generateContent(modelId: String, contents: List<Content>): String = ""
        }

        val engine = OnlineGeminiEngine(transport = transport, baseRetryDelayMs = 10L, maxRetries = 2)
        var cancelled = false
        try {
            engine.generateStream("hi").toList()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `maps 500 502 and 504 server errors cleanly and preserves history in buildContents`() {
        val engine = OnlineGeminiEngine(
            transport = object : GeminiTransport {
                override fun generateContentStream(modelId: String, contents: List<Content>): Flow<String> = flow {}
                override suspend fun generateContent(modelId: String, contents: List<Content>): String = ""
            }
        )

        assertEquals(OnlineErrorCategory.SERVER, engine.mapException(IOException("HTTP 500 Internal Server Error")).category)
        assertEquals(OnlineErrorCategory.SERVER, engine.mapException(IOException("HTTP 502 Bad Gateway")).category)
        assertEquals(OnlineErrorCategory.SERVER, engine.mapException(IOException("HTTP 504 Gateway Timeout")).category)

        val history = listOf(
            ChatMessage(sessionId = "s", text = "My name is Aman", isUser = true),
            ChatMessage(sessionId = "s", text = "Hello Aman!", isUser = false)
        )
        val contents = engine.buildContents("What is my name?", history, isVoice = false)
        // guidance user + model ack + 2 history turns + current prompt = 5
        assertEquals(5, contents.size)
    }
}
