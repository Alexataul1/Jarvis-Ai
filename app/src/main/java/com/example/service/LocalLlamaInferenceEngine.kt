package com.example.service

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.data.model.ChatMessage
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "LocalLlamaInferenceEngine"

enum class LocalLlamaFailureReason {
    MODEL_NOT_INSTALLED,
    INVALID_MODEL_FILE,
    INSUFFICIENT_MEMORY,
    NATIVE_ENGINE_UNAVAILABLE,
    INFERENCE_FAILED
}

class LocalLlamaException(
    val reason: LocalLlamaFailureReason,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

interface LlamaNativeBackend {
    fun isNativeAvailable(): Boolean
    fun initModel(
        modelPath: String,
        temperature: Float,
        maxTokens: Int,
        contextLength: Int,
        numThreads: Int,
        useMmap: Boolean
    ): Boolean
    fun applyChatTemplate(messages: List<Pair<String, String>>, addAssistantPrefix: Boolean): String?
    fun generateStream(
        prompt: String,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    )
    fun cancelGeneration()
    fun shutdown()
}

/**
 * Production Android JNI backend powered by com.llamatik:library (upstream llama.cpp + ggml).
 */
class LlamatikNativeBackend : LlamaNativeBackend {
    @Volatile
    private var nativeChecked = false
    @Volatile
    private var nativeAvailable = false

    override fun isNativeAvailable(): Boolean {
        if (!nativeChecked) {
            synchronized(this) {
                if (!nativeChecked) {
                    nativeAvailable = try {
                        // Touching LlamaBridge triggers System.loadLibrary("llama_jni")
                        LlamaBridge.getModelPath("check")
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Native llama_jni library unavailable on this ABI/runtime", e)
                        false
                    } catch (e: Throwable) {
                        Log.e(TAG, "Unexpected error checking native llama_jni availability", e)
                        false
                    }
                    nativeChecked = true
                }
            }
        }
        return nativeAvailable
    }

    override fun initModel(
        modelPath: String,
        temperature: Float,
        maxTokens: Int,
        contextLength: Int,
        numThreads: Int,
        useMmap: Boolean
    ): Boolean {
        if (!isNativeAvailable()) return false
        return try {
            val loaded = LlamaBridge.initGenerateModel(modelPath)
            if (loaded) {
                LlamaBridge.updateGenerateParams(
                    temperature = temperature,
                    maxTokens = maxTokens,
                    topP = 0.9f,
                    topK = 40,
                    repeatPenalty = 1.1f,
                    contextLength = contextLength,
                    numThreads = numThreads,
                    useMmap = useMmap,
                    flashAttention = false,
                    batchSize = 256,
                    gpuLayers = 0
                )
            }
            loaded
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize GGUF model at $modelPath", e)
            false
        }
    }

    override fun applyChatTemplate(
        messages: List<Pair<String, String>>,
        addAssistantPrefix: Boolean
    ): String? {
        if (!isNativeAvailable()) return null
        return try {
            LlamaBridge.applyChatTemplate(messages, addAssistantPrefix)
        } catch (e: Throwable) {
            Log.w(TAG, "applyChatTemplate unavailable, falling back to Llama 3.2 template", e)
            null
        }
    }

    override fun generateStream(
        prompt: String,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isNativeAvailable()) {
            onError("Native llama.cpp JNI engine is unavailable on this runtime.")
            return
        }
        LlamaBridge.generateStream(
            prompt,
            object : GenStream {
                override fun onDelta(text: String) = onDelta(text)
                override fun onComplete() = onComplete()
                override fun onError(message: String) = onError(message)
            }
        )
    }

    override fun cancelGeneration() {
        if (!isNativeAvailable()) return
        try {
            LlamaBridge.nativeCancelGenerate()
        } catch (e: Throwable) {
            Log.w(TAG, "cancelGeneration ignored: ${e.message}")
        }
    }

    override fun shutdown() {
        if (!isNativeAvailable()) return
        try {
            LlamaBridge.shutdown()
        } catch (e: Throwable) {
            Log.w(TAG, "shutdown ignored: ${e.message}")
        }
    }
}

/**
 * Genuine On-Device Meta Llama 3.2 GGUF Inference Engine via llama.cpp (Llamatik JNI):
 * - Strictly requires a verified downloaded GGUF file (no fake answers, no Knowledge Base replacement)
 * - Runs completely off Android main thread under single-model mutex protection
 * - Checks free RAM before loading to prevent OOM crashes
 * - Streams real native tokens with proper cancellation and native memory cleanup
 */
class LocalLlamaInferenceEngine(
    private val context: Context,
    private val modelManager: LocalLlamaModelManager,
    private val backend: LlamaNativeBackend = LlamatikNativeBackend(),
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val engineMutex = Mutex()
    @Volatile
    private var loadedModelPath: String? = null

    private val systemPrompt = """
        You are Jarvis, a helpful, concise offline AI assistant running locally on Android.
        Respond in natural, clear Devanagari Hindi (शुद्ध देवनागरी हिंदी) when the user writes or speaks in Hindi or Hinglish, and in clear English when the user writes in English. Keep answers accurate, helpful, and concise.
    """.trimIndent()

    fun isReadyModelInstalled(): Boolean {
        val activeId = modelManager.activeLlamaModelId.value
        return modelManager.getInstalledModelFile(activeId) != null
    }

    /**
     * Explicitly loads and verifies the GGUF model in llama.cpp without generating text.
     */
    suspend fun ensureModelLoaded(modelId: String = modelManager.activeLlamaModelId.value): Result<File> =
        withContext(inferenceDispatcher) {
            engineMutex.withLock {
                loadModelLocked(modelId)
            }
        }

    private fun loadModelLocked(modelId: String): Result<File> {
        val modelInfo = modelManager.availableModels.firstOrNull {
            it.id == modelId && it.type == ModelType.LLM_LLAMA
        } ?: return Result.failure(
            LocalLlamaException(
                LocalLlamaFailureReason.MODEL_NOT_INSTALLED,
                "Unknown local Llama model '$modelId'."
            )
        )

        val file = modelManager.getModelFile(modelInfo.fileName)
        if (!file.exists()) {
            return Result.failure(
                LocalLlamaException(
                    LocalLlamaFailureReason.MODEL_NOT_INSTALLED,
                    "${modelInfo.name} is not downloaded. Open Offline AI Brain & Models to download the verified GGUF file."
                )
            )
        }

        val integrity = modelManager.verifyModelIntegrity(file, modelInfo)
        if (integrity.isFailure) {
            return Result.failure(
                LocalLlamaException(
                    LocalLlamaFailureReason.INVALID_MODEL_FILE,
                    "Model file verification failed: ${integrity.exceptionOrNull()?.message}"
                )
            )
        }

        if (!backend.isNativeAvailable()) {
            return Result.failure(
                LocalLlamaException(
                    LocalLlamaFailureReason.NATIVE_ENGINE_UNAVAILABLE,
                    "Native llama.cpp inference library could not load on this runtime/architecture."
                )
            )
        }

        if (loadedModelPath == file.absolutePath) {
            return Result.success(file)
        }

        // Check available RAM before mapping GGUF weights
        val availMb = getAvailableRamMb()
        val requiredFreeMb = if (modelId == "llama_3_2_3b") 2200L else 900L
        if (availMb in 1 until requiredFreeMb) {
            return Result.failure(
                LocalLlamaException(
                    LocalLlamaFailureReason.INSUFFICIENT_MEMORY,
                    "Not enough free RAM to load ${modelInfo.name} ($availMb MB free, recommend >= $requiredFreeMb MB). Close background apps or use the 1B model."
                )
            )
        }

        if (loadedModelPath != null) {
            backend.shutdown()
            loadedModelPath = null
        }

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val ok = try {
            backend.initModel(
                modelPath = file.absolutePath,
                temperature = 0.7f,
                maxTokens = 512,
                contextLength = 2048,
                numThreads = cores,
                useMmap = true
            )
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM loading GGUF model ${file.name}", oom)
            backend.shutdown()
            return Result.failure(
                LocalLlamaException(
                    LocalLlamaFailureReason.INSUFFICIENT_MEMORY,
                    "Out of memory while loading ${modelInfo.name}. Switch to Llama 3.2 1B Instruct.",
                    oom
                )
            )
        }

        return if (ok) {
            loadedModelPath = file.absolutePath
            modelManager.markModelLoadVerified(modelId, true)
            Result.success(file)
        } else {
            modelManager.markModelLoadVerified(modelId, false)
            Result.failure(
                LocalLlamaException(
                    LocalLlamaFailureReason.INFERENCE_FAILED,
                    "llama.cpp rejected or failed to initialize ${file.name}."
                )
            )
        }
    }

    /**
     * Formats conversation history using GGUF's embedded chat template when loaded,
     * falling back cleanly to the official Llama 3.2 Instruct chat template.
     */
    fun buildPromptWithHistory(query: String, history: List<ChatMessage>): String {
        val turns = history
            .filter { !it.isError && it.text.isNotBlank() }
            .takeLast(6)

        val rolePairs = buildList {
            add("system" to systemPrompt)
            for (turn in turns) {
                add((if (turn.isUser) "user" else "assistant") to turn.text.trim())
            }
            add("user" to query.trim())
        }

        backend.applyChatTemplate(rolePairs, addAssistantPrefix = true)?.let { rendered ->
            if (rendered.isNotBlank()) return rendered
        }

        val sb = StringBuilder("<|begin_of_text|>")
        for ((role, content) in rolePairs) {
            sb.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
            sb.append(content).append("<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    /**
     * Streams genuine tokens emitted by llama.cpp off the main thread.
     * Throws LocalLlamaException if no verified model is available or inference fails.
     */
    fun generateStream(
        query: String,
        history: List<ChatMessage> = emptyList(),
        modelId: String = modelManager.activeLlamaModelId.value
    ): Flow<String> = callbackFlow {
        engineMutex.withLock {
            val loadResult = loadModelLocked(modelId)
            if (loadResult.isFailure) {
                val err = loadResult.exceptionOrNull()!!
                close(err)
                return@withLock
            }

            val prompt = buildPromptWithHistory(query, history)
            var streamError: LocalLlamaException? = null

            try {
                backend.generateStream(
                    prompt = prompt,
                    onDelta = { token ->
                        if (token.isNotEmpty()) {
                            trySend(token)
                        }
                    },
                    onComplete = {
                        // Stream completed naturally
                    },
                    onError = { message ->
                        streamError = LocalLlamaException(
                            LocalLlamaFailureReason.INFERENCE_FAILED,
                            "Local Llama inference error: $message"
                        )
                    }
                )
            } catch (e: CancellationException) {
                backend.cancelGeneration()
                throw e
            } catch (oom: OutOfMemoryError) {
                backend.shutdown()
                loadedModelPath = null
                close(
                    LocalLlamaException(
                        LocalLlamaFailureReason.INSUFFICIENT_MEMORY,
                        "Ran out of memory during local inference. Close background apps or use Llama 3.2 1B.",
                        oom
                    )
                )
                return@withLock
            } catch (t: Throwable) {
                close(
                    LocalLlamaException(
                        LocalLlamaFailureReason.INFERENCE_FAILED,
                        "Local inference failed: ${t.message ?: "unknown native error"}",
                        t
                    )
                )
                return@withLock
            }

            if (streamError != null) {
                close(streamError)
            } else {
                close()
            }
        }

        awaitClose {
            backend.cancelGeneration()
        }
    }.flowOn(inferenceDispatcher)

    suspend fun generateContent(
        query: String,
        history: List<ChatMessage> = emptyList(),
        modelId: String = modelManager.activeLlamaModelId.value
    ): String {
        val sb = StringBuilder()
        generateStream(query, history, modelId).collect { sb.append(it) }
        return sb.toString().trim()
    }

    fun cancelGeneration() {
        backend.cancelGeneration()
    }

    fun unloadModel() {
        backend.cancelGeneration()
        backend.shutdown()
        loadedModelPath = null
    }

    private fun getAvailableRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }
}
