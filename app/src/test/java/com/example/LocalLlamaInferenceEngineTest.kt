package com.example

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatMessage
import com.example.service.LlamaNativeBackend
import com.example.service.LocalAiModelInfo
import com.example.service.LocalLlamaException
import com.example.service.LocalLlamaFailureReason
import com.example.service.LocalLlamaInferenceEngine
import com.example.service.LocalLlamaModelManager
import com.example.service.ModelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLlamaInferenceEngineTest {

    private fun testModelCatalog(): List<LocalAiModelInfo> = listOf(
        LocalAiModelInfo(
            id = "llama_3_2_1b",
            name = "Meta Llama 3.2 1B Instruct",
            subtitle = "Test",
            description = "Test",
            type = ModelType.LLM_LLAMA,
            repository = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            format = "GGUF",
            license = "Meta Llama 3.2",
            sizeBytes = 1024L,
            minValidBytes = 1024L,
            sizeDisplay = "1 KB",
            minRamGb = 3f,
            recommendedRamGb = 6f,
            fileName = "engine_test_1b.gguf",
            downloadUrl = "https://example.test/1b.gguf",
            isRecommendedForDevice = true
        )
    )

    private fun createValidGguf(manager: LocalLlamaModelManager, fileName: String) {
        val file = manager.getModelFile(fileName)
        file.parentFile?.mkdirs()
        val bytes = ByteArray(1024)
        bytes[0] = 0x47
        bytes[1] = 0x47
        bytes[2] = 0x55
        bytes[3] = 0x46
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(3)
        file.writeBytes(bytes)
    }

    private fun configureRamMb(context: Context, availMb: Long, totalMb: Long = 8192L) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val shadowAm = Shadows.shadowOf(am)
        shadowAm.setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = totalMb * 1024L * 1024L
            availMem = availMb * 1024L * 1024L
        })
    }

    @Test
    fun `missing GGUF model fails with MODEL_NOT_INSTALLED instead of returning fake answers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configureRamMb(context, 4096L)
        val manager = LocalLlamaModelManager(context, catalogOverride = testModelCatalog())
        manager.deleteModel("llama_3_2_1b")

        val backend = FakeLlamaBackend()
        val engine = LocalLlamaInferenceEngine(context, manager, backend, Dispatchers.IO)

        var caught: LocalLlamaException? = null
        try {
            engine.generateStream("hi").toList()
        } catch (e: LocalLlamaException) {
            caught = e
        }

        assertEquals(LocalLlamaFailureReason.MODEL_NOT_INSTALLED, caught?.reason)
        assertEquals(0, backend.initCount)
    }

    @Test
    fun `insufficient RAM aborts model loading safely before native init`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configureRamMb(context, 400L) // < 900 MB minimum for 1B
        val manager = LocalLlamaModelManager(context, catalogOverride = testModelCatalog())
        createValidGguf(manager, "engine_test_1b.gguf")

        val backend = FakeLlamaBackend()
        val engine = LocalLlamaInferenceEngine(context, manager, backend, Dispatchers.IO)

        val loadRes = engine.ensureModelLoaded("llama_3_2_1b")
        assertTrue(loadRes.isFailure)
        val err = loadRes.exceptionOrNull() as LocalLlamaException
        assertEquals(LocalLlamaFailureReason.INSUFFICIENT_MEMORY, err.reason)
        assertEquals(0, backend.initCount)

        manager.deleteModel("llama_3_2_1b")
    }

    @Test
    fun `real streaming emits tokens off main thread includes history and supports cancellation and cleanup`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configureRamMb(context, 4500L)
        val manager = LocalLlamaModelManager(context, catalogOverride = testModelCatalog())
        createValidGguf(manager, "engine_test_1b.gguf")

        val backend = FakeLlamaBackend(
            tokensToEmit = listOf("नमस्ते! ", "मैं आपकी ", "मदद कर सकता हूँ।")
        )
        val engine = LocalLlamaInferenceEngine(context, manager, backend, Dispatchers.IO)

        val history = listOf(
            ChatMessage(sessionId = "s1", text = "Mera naam Ravi hai", isUser = true),
            ChatMessage(sessionId = "s1", text = "नमस्ते रवि!", isUser = false)
        )

        val chunks = engine.generateStream("Mera naam kya hai?", history, "llama_3_2_1b").toList()
        assertEquals("नमस्ते! मैं आपकी मदद कर सकता हूँ।", chunks.joinToString(""))
        assertEquals(1, backend.initCount)
        assertTrue(backend.lastPrompt.contains("Mera naam Ravi hai"))
        assertTrue(backend.lastPrompt.contains("Mera naam kya hai?"))

        // Cancellation test
        val slowBackend = FakeLlamaBackend(
            tokensToEmit = List(50) { "tok " },
            perTokenSleepMs = 40L
        )
        val cancelEngine = LocalLlamaInferenceEngine(context, manager, slowBackend, Dispatchers.IO)
        val job = async(Dispatchers.Default) {
            cancelEngine.generateStream("count slowly", emptyList(), "llama_3_2_1b").toList()
        }
        delay(120L)
        cancelEngine.cancelGeneration()
        job.cancel(CancellationException("User stopped"))
        try {
            job.await()
        } catch (_: CancellationException) {
        }
        assertTrue(slowBackend.cancelCalledCount > 0)

        cancelEngine.unloadModel()
        assertTrue(slowBackend.shutdownCalledCount > 0)

        manager.deleteModel("llama_3_2_1b")
    }

    private class FakeLlamaBackend(
        private val tokensToEmit: List<String> = listOf("Hello"),
        private val perTokenSleepMs: Long = 0L
    ) : LlamaNativeBackend {
        var initCount = 0
        var cancelCalledCount = 0
        var shutdownCalledCount = 0
        var lastPrompt = ""
        @Volatile
        private var cancelled = false

        override fun isNativeAvailable(): Boolean = true

        override fun initModel(
            modelPath: String,
            temperature: Float,
            maxTokens: Int,
            contextLength: Int,
            numThreads: Int,
            useMmap: Boolean
        ): Boolean {
            initCount++
            return true
        }

        override fun applyChatTemplate(messages: List<Pair<String, String>>, addAssistantPrefix: Boolean): String? = null

        override fun generateStream(
            prompt: String,
            onDelta: (String) -> Unit,
            onComplete: () -> Unit,
            onError: (String) -> Unit
        ) {
            cancelled = false
            lastPrompt = prompt
            for (token in tokensToEmit) {
                if (cancelled) break
                if (perTokenSleepMs > 0) Thread.sleep(perTokenSleepMs)
                if (!cancelled) onDelta(token)
            }
            onComplete()
        }

        override fun cancelGeneration() {
            cancelled = true
            cancelCalledCount++
        }

        override fun shutdown() {
            shutdownCalledCount++
        }
    }
}
