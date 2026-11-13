package com.example.service

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.ChatMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.APINotConfiguredException
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.ContentBlockedException
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ServiceConnectionHandshakeFailedException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "OnlineGeminiEngine"

enum class OnlineErrorCategory {
    QUOTA_EXCEEDED,
    AUTHENTICATION,
    NETWORK,
    SERVER,
    TIMEOUT,
    CONTENT_BLOCKED,
    MODEL_UNAVAILABLE,
    UNKNOWN
}

class OnlineGeminiException(
    val category: OnlineErrorCategory,
    val httpStatus: Int?,
    val userMessage: String,
    val retryable: Boolean,
    cause: Throwable? = null
) : Exception(userMessage, cause)

interface GeminiTransport {
    fun generateContentStream(modelId: String, contents: List<Content>): Flow<String>
    suspend fun generateContent(modelId: String, contents: List<Content>): String
}

class FirebaseGeminiTransport : GeminiTransport {
    private val fastModel by lazy {
        Firebase.ai.generativeModel(modelName = OnlineGeminiEngine.MODEL_FAST)
    }

    private val primaryModel by lazy {
        Firebase.ai.generativeModel(modelName = OnlineGeminiEngine.MODEL_DEFAULT)
    }

    private fun resolveModel(modelId: String) = when (modelId) {
        OnlineGeminiEngine.MODEL_FAST -> fastModel
        OnlineGeminiEngine.MODEL_DEFAULT -> primaryModel
        else -> Firebase.ai.generativeModel(modelName = modelId)
    }

    override fun generateContentStream(modelId: String, contents: List<Content>): Flow<String> = flow {
        val generativeModel = resolveModel(modelId)
        generativeModel.generateContentStream(contents).collect { chunk ->
            val text = chunk.text.orEmpty()
            if (text.isNotEmpty()) {
                emit(text)
            }
        }
    }

    override suspend fun generateContent(modelId: String, contents: List<Content>): String {
        val generativeModel = resolveModel(modelId)
        return generativeModel.generateContent(contents).text.orEmpty()
    }
}

/**
 * Online Gemini Engine via Firebase AI Logic (firebase-bom 34.17.0 / firebase-ai 17.15.0):
 * - Supported Gemini 3.x models: gemini-3.5-flash-lite (fast text/voice) and gemini-3.8-flash (default/multimodal)
 * - Explicit classification for Quota (429), Auth/App Check (401/403), Network, Server (500/502/503/504), and Timeout
 * - Bounded exponential backoff retry that NEVER retries CancellationException
 * - Maintains recent conversation history and multimodal image analysis
 */
class OnlineGeminiEngine(
    private val transport: GeminiTransport = FirebaseGeminiTransport(),
    private val baseRetryDelayMs: Long = 600L,
    private val maxRetries: Int = 2,
    private val streamTimeoutMs: Long = 20_000L,
    private val unaryTimeoutMs: Long = 20_000L
) {

    companion object {
        const val MODEL_FAST = "gemini-3.5-flash-lite"
        const val MODEL_DEFAULT = "gemini-3.8-flash"
    }

    private val voiceSystemGuidance = """
        You are Jarvis, a warm, helpful conversational AI assistant.
        Respond in natural Devanagari Hindi whenever the user writes or speaks in Hindi or Hinglish, and in clear English when the user uses English.
        Keep spoken replies concise (1 to 3 short plain-text sentences) without markdown symbols, bullet points, or emojis.
    """.trimIndent()

    private val textSystemGuidance = """
        You are Jarvis, an intelligent, helpful AI assistant.
        Respond in clear, natural Devanagari Hindi whenever the user writes in Hindi or Hinglish, and in clear English when the user writes in English.
    """.trimIndent()

    /**
     * Streams Gemini response tokens with bounded exponential-backoff retry.
     * Note: Firebase AI returns text tokens; spoken audio output is handled separately by SpeechManager (Android TTS).
     */
    fun generateStream(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        isVoice: Boolean = false
    ): Flow<String> = flow {
        val contents = buildContents(prompt, history, isVoice)
        val primaryModelId = MODEL_FAST
        val secondaryModelId = MODEL_DEFAULT

        var emittedAnyToken = false
        var lastError: OnlineGeminiException? = null

        val candidateModels = listOf(primaryModelId, secondaryModelId)
        for (modelId in candidateModels) {
            var attempt = 0
            while (attempt <= maxRetries) {
                if (attempt > 0) {
                    val backoffMs = baseRetryDelayMs * (1L shl (attempt - 1))
                    delay(backoffMs)
                }

                try {
                    withTimeout(streamTimeoutMs) {
                        transport.generateContentStream(modelId, contents).collect { chunk ->
                            if (chunk.isNotEmpty()) {
                                emittedAnyToken = true
                                emit(chunk)
                            }
                        }
                    }
                    if (emittedAnyToken) {
                        return@flow
                    }
                    throw OnlineGeminiException(
                        category = OnlineErrorCategory.UNKNOWN,
                        httpStatus = null,
                        userMessage = "Gemini returned an empty response.",
                        retryable = true
                    )
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) {
                        val mapped = mapException(e)
                        lastError = mapped
                        Log.w(TAG, "Stream timeout on $modelId (attempt ${attempt + 1})", e)
                        if (emittedAnyToken) throw mapped
                    } else {
                        // Crucial: Never retry coroutine cancellation
                        throw e
                    }
                } catch (t: Throwable) {
                    val mapped = mapException(t)
                    lastError = mapped
                    Log.e(TAG, "Gemini stream error [${mapped.category}] on $modelId (attempt ${attempt + 1}): ${t.message}", t)

                    // Do not duplicate partial stream or retry non-retryable auth/content blocks
                    if (emittedAnyToken || !mapped.retryable) {
                        throw mapped
                    }
                }
                attempt++
            }
        }

        throw (lastError ?: OnlineGeminiException(
            category = OnlineErrorCategory.UNKNOWN,
            httpStatus = null,
            userMessage = "Unable to reach Gemini right now. Please try again.",
            retryable = false
        ))
    }

    /**
     * Generates a full response with bounded exponential-backoff retry and error mapping.
     */
    suspend fun generateContent(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        isVoice: Boolean = false
    ): Result<String> {
        val contents = buildContents(prompt, history, isVoice)
        val candidateModels = listOf(MODEL_FAST, MODEL_DEFAULT)
        var lastError: OnlineGeminiException? = null

        for (modelId in candidateModels) {
            var attempt = 0
            while (attempt <= maxRetries) {
                if (attempt > 0) {
                    delay(baseRetryDelayMs * (1L shl (attempt - 1)))
                }
                try {
                    val result = withTimeout(unaryTimeoutMs) {
                        transport.generateContent(modelId, contents)
                    }.trim()
                    if (result.isNotBlank()) {
                        return Result.success(result)
                    }
                    lastError = OnlineGeminiException(
                        category = OnlineErrorCategory.UNKNOWN,
                        httpStatus = null,
                        userMessage = "Gemini returned an empty response.",
                        retryable = true
                    )
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) {
                        lastError = mapException(e)
                    } else {
                        throw e
                    }
                } catch (t: Throwable) {
                    val mapped = mapException(t)
                    lastError = mapped
                    Log.e(TAG, "Gemini unary error [${mapped.category}] on $modelId: ${t.message}", t)
                    if (!mapped.retryable) {
                        return Result.failure(mapped)
                    }
                }
                attempt++
            }
        }

        return Result.failure(
            lastError ?: OnlineGeminiException(
                category = OnlineErrorCategory.UNKNOWN,
                httpStatus = null,
                userMessage = "Gemini request failed. Please try again.",
                retryable = false
            )
        )
    }

    /**
     * Multimodal image analysis via gemini-3.8-flash with retry & explicit error feedback.
     */
    suspend fun analyzeImage(bitmap: Bitmap, prompt: String): Result<String> {
        val effectivePrompt = if (prompt.isBlank()) {
            "Analyze this image clearly. Describe what you see, extract any visible text, and answer helpfully in clear Hindi or English."
        } else {
            prompt.trim()
        }
        val imageContent = content(role = "user") {
            image(bitmap)
            text(effectivePrompt)
        }

        var lastError: OnlineGeminiException? = null
        var attempt = 0
        while (attempt <= maxRetries) {
            if (attempt > 0) {
                delay(baseRetryDelayMs * (1L shl (attempt - 1)))
            }
            try {
                val reply = withTimeout(unaryTimeoutMs) {
                    transport.generateContent(MODEL_DEFAULT, listOf(imageContent))
                }.trim()
                if (reply.isNotBlank()) {
                    return Result.success(reply)
                }
                lastError = OnlineGeminiException(
                    category = OnlineErrorCategory.UNKNOWN,
                    httpStatus = null,
                    userMessage = "No image analysis returned from Gemini.",
                    retryable = true
                )
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    lastError = mapException(e)
                } else {
                    throw e
                }
            } catch (t: Throwable) {
                val mapped = mapException(t)
                lastError = mapped
                Log.e(TAG, "Gemini image analysis error [${mapped.category}]: ${t.message}", t)
                if (!mapped.retryable) {
                    return Result.failure(mapped)
                }
            }
            attempt++
        }

        return Result.failure(
            lastError ?: OnlineGeminiException(
                category = OnlineErrorCategory.UNKNOWN,
                httpStatus = null,
                userMessage = "Image analysis failed. Please try again.",
                retryable = false
            )
        )
    }

    suspend fun fetchRealtimeUpdates(): String {
        return generateContent("Give a concise 2-sentence summary of AI developments today in Hindi and English.")
            .getOrElse { err -> err.message ?: "Unable to fetch Gemini updates." }
    }

    fun buildContents(prompt: String, history: List<ChatMessage>, isVoice: Boolean): List<Content> {
        val guidance = if (isVoice) voiceSystemGuidance else textSystemGuidance
        val contents = mutableListOf<Content>()

        // Prepend system guidance inside initial user context to ensure consistent behavior across all backends
        val recentTurns = history
            .filter { !it.isError && it.text.isNotBlank() && it.imageUri == null }
            .takeLast(6)

        if (recentTurns.isEmpty()) {
            contents.add(content(role = "user") {
                text("$guidance\n\nUser request: ${prompt.trim()}")
            })
        } else {
            contents.add(content(role = "user") { text(guidance) })
            contents.add(content(role = "model") { text("Understood. Ready to help.") })
            for (turn in recentTurns) {
                contents.add(content(role = if (turn.isUser) "user" else "model") {
                    text(turn.text.trim())
                })
            }
            contents.add(content(role = "user") { text(prompt.trim()) })
        }
        return contents
    }

    fun mapException(t: Throwable): OnlineGeminiException {
        if (t is OnlineGeminiException) return t

        val msg = (t.message ?: t.cause?.message).orEmpty()
        val httpCode = extractHttpStatusCode(msg)

        return when {
            t is QuotaExceededException || httpCode == 429 || msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || msg.contains("quota", ignoreCase = true) ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.QUOTA_EXCEEDED,
                    httpStatus = httpCode ?: 429,
                    userMessage = "Gemini rate/quota limit reached (HTTP 429). Wait a moment and try again.",
                    retryable = true,
                    cause = t
                )

            t is InvalidAPIKeyException || t is ServiceDisabledException || t is APINotConfiguredException ||
                httpCode == 401 || httpCode == 403 || msg.contains("App Check", ignoreCase = true) ||
                msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.AUTHENTICATION,
                    httpStatus = httpCode ?: 403,
                    userMessage = "Firebase AI authentication or App Check verification failed (HTTP ${httpCode ?: 403}). Verify Firebase AI Logic & App Check setup.",
                    retryable = false,
                    cause = t
                )

            httpCode == 404 || msg.contains("NOT_FOUND", ignoreCase = true) || msg.contains("model", ignoreCase = true) && msg.contains("not found", ignoreCase = true) ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.MODEL_UNAVAILABLE,
                    httpStatus = 404,
                    userMessage = "Requested Gemini model is unavailable (HTTP 404). Update model configuration.",
                    retryable = false,
                    cause = t
                )

            t is PromptBlockedException || t is ContentBlockedException || t is ResponseStoppedException ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.CONTENT_BLOCKED,
                    httpStatus = null,
                    userMessage = "Response was blocked by Gemini safety filters. Try rephrasing your question.",
                    retryable = false,
                    cause = t
                )

            httpCode in listOf(500, 502, 503, 504) || t is ServerException ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.SERVER,
                    httpStatus = httpCode ?: 503,
                    userMessage = "Gemini server is temporarily busy or unavailable (HTTP ${httpCode ?: 503}). Please retry shortly.",
                    retryable = true,
                    cause = t
                )

            t is RequestTimeoutException || t is TimeoutCancellationException || t is SocketTimeoutException ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.TIMEOUT,
                    httpStatus = 504,
                    userMessage = "Gemini request timed out. Check your connection speed and try again.",
                    retryable = true,
                    cause = t
                )

            t is UnknownHostException || t is ConnectException || t is ServiceConnectionHandshakeFailedException || t is IOException ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.NETWORK,
                    httpStatus = null,
                    userMessage = "Network connection failed while reaching Gemini. Check internet connectivity.",
                    retryable = true,
                    cause = t
                )

            else ->
                OnlineGeminiException(
                    category = OnlineErrorCategory.UNKNOWN,
                    httpStatus = httpCode,
                    userMessage = "Gemini request failed: ${msg.ifBlank { "Unexpected error" }}",
                    retryable = false,
                    cause = t
                )
        }
    }

    private fun extractHttpStatusCode(message: String): Int? {
        val codes = listOf(429, 500, 502, 503, 504, 401, 403, 404, 400)
        for (code in codes) {
            if (Regex("""\b$code\b""").containsMatchIn(message)) {
                return code
            }
        }
        return null
    }
}
