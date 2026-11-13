package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.LocalAiModelInfo
import com.example.service.LocalLlamaModelManager
import com.example.service.ModelInstallState
import com.example.service.ModelType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLlamaModelManagerTest {

    private fun buildValidGgufBytes(totalSize: Int = 4096): ByteArray {
        val bytes = ByteArray(totalSize)
        // 'GGUF' magic + version 3 little-endian
        bytes[0] = 0x47
        bytes[1] = 0x47
        bytes[2] = 0x55
        bytes[3] = 0x46
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(3)
        return bytes
    }

    private fun buildValidWhisperBytes(totalSize: Int = 4096): ByteArray {
        val bytes = ByteArray(totalSize)
        // 'lmgg' magic
        bytes[0] = 0x6C
        bytes[1] = 0x6D
        bytes[2] = 0x67
        bytes[3] = 0x67
        return bytes
    }

    private fun testCatalog(sizeBytes: Long = 4096L): List<LocalAiModelInfo> = listOf(
        LocalAiModelInfo(
            id = "llama_3_2_1b",
            name = "Test Llama 1B",
            subtitle = "Default 8GB",
            description = "Test GGUF",
            type = ModelType.LLM_LLAMA,
            repository = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            format = "GGUF (Q4_K_M)",
            license = "Meta Llama 3.2",
            sizeBytes = sizeBytes,
            minValidBytes = sizeBytes,
            sizeDisplay = "4 KB",
            minRamGb = 3f,
            recommendedRamGb = 6f,
            fileName = "test_llama_1b.gguf",
            downloadUrl = "https://example.test/llama.gguf",
            isRecommendedForDevice = true
        ),
        LocalAiModelInfo(
            id = "whisper_base_stt",
            name = "Test Whisper Base",
            subtitle = "STT",
            description = "Test GGML",
            type = ModelType.STT_WHISPER,
            repository = "ggerganov/whisper.cpp",
            format = "GGML",
            license = "MIT",
            sizeBytes = sizeBytes,
            minValidBytes = sizeBytes,
            sizeDisplay = "4 KB",
            minRamGb = 2f,
            recommendedRamGb = 4f,
            fileName = "test_whisper.bin",
            downloadUrl = "https://example.test/whisper.bin",
            isRecommendedForDevice = true
        )
    )

    private fun mockHttpClient(handler: (okhttp3.Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
    }

    private fun setupFakeStorage(context: Context) {
        val dir = context.filesDir
        dir.mkdirs()
        // 4 GB total / 4 GB free blocks
        ShadowStatFs.registerStats(dir.absolutePath, 1024 * 1024, 1024 * 1024, 1024 * 1024)
    }

    @Test
    fun `production thresholds reject tiny fake GGUF files and HTML error payloads`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prodManager = LocalLlamaModelManager(context)
        val prod1B = prodManager.availableModels.first { it.id == "llama_3_2_1b" }
        assertTrue(prod1B.isRecommendedForDevice)
        assertEquals(807_694_464L, prod1B.sizeBytes)

        val tinyFake = File(context.cacheDir, "fake_tiny.gguf").apply {
            writeBytes("GGUF_REAL_QUANTIZED_META_LLAMA_3_2_MODEL".toByteArray())
        }
        val tinyResult = prodManager.verifyModelIntegrity(tinyFake, prod1B)
        assertTrue(tinyResult.isFailure)
        tinyFake.delete()

        val smallModelSpec = testCatalog(64L).first()
        val htmlFile = File(context.cacheDir, "error_page.gguf").apply {
            writeText("<html><body>404 Not Found</body></html>" + " ".repeat(40))
        }
        assertTrue(prodManager.verifyModelIntegrity(htmlFile, smallModelSpec).isFailure)
        htmlFile.delete()
    }

    @Test
    fun `download rejects HTTP 404 and HTML responses without leaving installed state`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = mockHttpClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("<html>Not Found</html>".toResponseBody("text/html".toMediaType()))
                .build()
        }
        val manager = LocalLlamaModelManager(context, httpClient = client, catalogOverride = testCatalog())
        manager.deleteModel("llama_3_2_1b")

        val result = manager.downloadModelBlocking("llama_3_2_1b")
        assertTrue(result.isFailure)
        assertFalse(manager.isModelInstalled("llama_3_2_1b"))
        assertNull(manager.getInstalledModelFile("llama_3_2_1b"))
        assertTrue(manager.modelStates.value["llama_3_2_1b"] is ModelInstallState.Error)
    }

    @Test
    fun `download rejects HTML body returned with HTTP 200`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = mockHttpClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<!DOCTYPE html><html>Rate Limit</html>".toResponseBody("text/html".toMediaType()))
                .build()
        }
        val manager = LocalLlamaModelManager(context, httpClient = client, catalogOverride = testCatalog(32L))
        manager.deleteModel("llama_3_2_1b")

        val result = manager.downloadModelBlocking("llama_3_2_1b")
        assertTrue(result.isFailure)
        assertFalse(manager.isModelInstalled("llama_3_2_1b"))
    }

    @Test
    fun `download verifies valid GGUF and promotes part file atomically`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        setupFakeStorage(context)
        val payload = buildValidGgufBytes(4096)
        val client = mockHttpClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Length", payload.size.toString())
                .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
        val manager = LocalLlamaModelManager(context, httpClient = client, catalogOverride = testCatalog(4096L))
        manager.deleteModel("llama_3_2_1b")

        val result = manager.downloadModelBlocking("llama_3_2_1b")
        assertTrue(result.isSuccess)
        val file = result.getOrNull()
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertEquals(4096L, file.length())
        assertFalse(manager.getPartFile("test_llama_1b.gguf").exists())
        assertTrue(manager.isModelInstalled("llama_3_2_1b"))

        manager.deleteModel("llama_3_2_1b")
    }

    @Test
    fun `download supports HTTP 206 partial resume from existing part file`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        setupFakeStorage(context)
        val fullPayload = buildValidGgufBytes(4096)
        val splitPoint = 1500
        var sawRangeHeader: String? = null

        val client = mockHttpClient { req ->
            sawRangeHeader = req.header("Range")
            val slice = fullPayload.copyOfRange(splitPoint, fullPayload.size)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes $splitPoint-${fullPayload.size - 1}/${fullPayload.size}")
                .header("Content-Length", slice.size.toString())
                .body(slice.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        val manager = LocalLlamaModelManager(context, httpClient = client, catalogOverride = testCatalog(4096L))
        manager.deleteModel("llama_3_2_1b")
        val partFile = manager.getPartFile("test_llama_1b.gguf")
        partFile.parentFile?.mkdirs()
        partFile.writeBytes(fullPayload.copyOfRange(0, splitPoint))

        val result = manager.downloadModelBlocking("llama_3_2_1b")
        assertEquals("bytes=$splitPoint-", sawRangeHeader)
        assertTrue(result.isSuccess)
        assertEquals(4096L, result.getOrNull()?.length())

        manager.deleteModel("llama_3_2_1b")
    }

    @Test
    fun `download validates whisper GGML magic header and fails on low storage`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val whisperPayload = buildValidWhisperBytes(4096)
        val client = mockHttpClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Length", whisperPayload.size.toString())
                .body(whisperPayload.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        // Low storage subclass fails before download
        val lowStorageManager = object : LocalLlamaModelManager(context, httpClient = client, catalogOverride = testCatalog(4096L)) {
            override fun availableInternalStorageBytes(): Long = 1024L
        }
        val lowRes = lowStorageManager.downloadModelBlocking("whisper_base_stt")
        assertTrue(lowRes.isFailure)
        assertTrue(lowRes.exceptionOrNull()?.message?.contains("Insufficient storage") == true)

        // Normal storage succeeds for valid GGML whisper binary
        setupFakeStorage(context)
        val okManager = LocalLlamaModelManager(context, httpClient = client, catalogOverride = testCatalog(4096L))
        okManager.deleteModel("whisper_base_stt")
        val okRes = okManager.downloadModelBlocking("whisper_base_stt")
        assertTrue(okRes.isSuccess)
        assertNotNull(okManager.getInstalledWhisperModelFile())
        okManager.deleteModel("whisper_base_stt")
    }
}
