package com.example.service

import android.graphics.Bitmap
import com.example.data.model.AssistantMode
import com.example.data.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

data class RoutedReply(
    val text: String,
    val isOfflineEngine: Boolean,
    val engineLabel: String,
    val isError: Boolean = false
)

/**
 * Pure, deterministic router coordinating ONLINE, OFFLINE, and AUTO hybrid execution:
 * - ONLINE: strictly Gemini; never falls back silently to local models.
 * - OFFLINE: strictly on-device llama.cpp GGUF (or explicit device actions); never calls cloud.
 * - AUTO: tries Gemini when online; falls back to genuine installed Llama GGUF if Gemini fails or offline.
 */
class HybridAssistantRouter(
    private val onlineStreamFn: (prompt: String, history: List<ChatMessage>, isVoice: Boolean) -> Flow<String>,
    private val imageAnalyzeFn: suspend (bitmap: Bitmap, prompt: String) -> Result<String>,
    private val llamaReadyFn: () -> Boolean,
    private val llamaStreamFn: (prompt: String, history: List<ChatMessage>) -> Flow<String>,
    private val deviceActionFn: (prompt: String) -> OfflineResponse?,
    private val mapOnlineErrorFn: (Throwable) -> OnlineGeminiException
) {
    constructor(
        onlineEngine: OnlineGeminiEngine,
        llamaEngine: LocalLlamaInferenceEngine,
        offlineEngine: OfflineAssistantEngine
    ) : this(
        onlineStreamFn = { p, h, v -> onlineEngine.generateStream(p, h, v) },
        imageAnalyzeFn = { bmp, p -> onlineEngine.analyzeImage(bmp, p) },
        llamaReadyFn = { llamaEngine.isReadyModelInstalled() },
        llamaStreamFn = { p, h -> llamaEngine.generateStream(p, h) },
        deviceActionFn = { p -> offlineEngine.processDeviceActionOrNull(p) },
        mapOnlineErrorFn = { t -> onlineEngine.mapException(t) }
    )

    suspend fun routeMessage(
        prompt: String,
        history: List<ChatMessage>,
        mode: AssistantMode,
        isOnline: Boolean,
        isVoice: Boolean,
        bitmap: Bitmap? = null,
        hasImageAttachment: Boolean = (bitmap != null),
        onStreamChunk: suspend (chunk: String, isOffline: Boolean, engineLabel: String) -> Unit
    ): RoutedReply {
        // 1. Explicit device utilities (dialing, app launching, battery, date/time, simple arithmetic)
        if (!hasImageAttachment) {
            deviceActionFn(prompt)?.let { action ->
                return RoutedReply(
                    text = action.text,
                    isOfflineEngine = true,
                    engineLabel = "Device Control • Offline",
                    isError = false
                )
            }
        }

        // 2. Multimodal Image Queries
        if (hasImageAttachment) {
            return handleImageQuery(
                prompt = prompt,
                mode = mode,
                isOnline = isOnline,
                bitmap = bitmap
            )
        }

        // 3. Text & Voice Conversational Routing
        return when (mode) {
            AssistantMode.ONLINE -> executeOnlineOnly(prompt, history, isOnline, isVoice, onStreamChunk)
            AssistantMode.OFFLINE -> executeOfflineOnly(prompt, history, onStreamChunk)
            AssistantMode.AUTO -> executeAuto(prompt, history, isOnline, isVoice, onStreamChunk)
        }
    }

    private suspend fun handleImageQuery(
        prompt: String,
        mode: AssistantMode,
        isOnline: Boolean,
        bitmap: Bitmap?
    ): RoutedReply {
        if (mode == AssistantMode.OFFLINE) {
            return RoutedReply(
                text = "Offline mode uses text-only Meta Llama 3.2 and cannot analyze images. Switch to Online or Auto with internet to analyze photos.",
                isOfflineEngine = true,
                engineLabel = "Offline Mode • Text Only",
                isError = true
            )
        }
        if (!isOnline) {
            return RoutedReply(
                text = "Image analysis requires an active internet connection for Gemini Vision (local Llama is text-only).",
                isOfflineEngine = false,
                engineLabel = "Gemini Vision • Offline",
                isError = true
            )
        }
        if (bitmap == null) {
            return RoutedReply(
                text = "Unable to decode the selected image file.",
                isOfflineEngine = false,
                engineLabel = "Image Error",
                isError = true
            )
        }

        val res = imageAnalyzeFn(bitmap, prompt)
        return res.fold(
            onSuccess = { reply ->
                RoutedReply(
                    text = reply,
                    isOfflineEngine = false,
                    engineLabel = "Gemini • Vision",
                    isError = false
                )
            },
            onFailure = { err ->
                val mapped = mapOnlineErrorFn(err)
                RoutedReply(
                    text = mapped.userMessage,
                    isOfflineEngine = false,
                    engineLabel = "Gemini Vision Error",
                    isError = true
                )
            }
        )
    }

    private suspend fun executeOnlineOnly(
        prompt: String,
        history: List<ChatMessage>,
        isOnline: Boolean,
        isVoice: Boolean,
        onStreamChunk: suspend (String, Boolean, String) -> Unit
    ): RoutedReply {
        if (!isOnline) {
            return RoutedReply(
                text = "Online mode is selected, but there is no internet connection. Connect to Wi-Fi/mobile data or switch to Auto / Offline.",
                isOfflineEngine = false,
                engineLabel = "Gemini • No Internet",
                isError = true
            )
        }

        val sb = StringBuilder()
        return try {
            onlineStreamFn(prompt, history, isVoice).collect { chunk ->
                sb.append(chunk)
                onStreamChunk(chunk, false, "Gemini • Online")
            }
            val finalText = sb.toString().trim()
            if (finalText.isNotEmpty()) {
                RoutedReply(
                    text = finalText,
                    isOfflineEngine = false,
                    engineLabel = "Gemini • Online",
                    isError = false
                )
            } else {
                RoutedReply(
                    text = "Gemini returned an empty response. Please try again.",
                    isOfflineEngine = false,
                    engineLabel = "Gemini Error",
                    isError = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val mapped = mapOnlineErrorFn(t)
            val partial = sb.toString().trim()
            if (partial.isNotEmpty()) {
                RoutedReply(
                    text = "$partial\n\n[Interrupted: ${mapped.userMessage}]",
                    isOfflineEngine = false,
                    engineLabel = "Gemini • Partial",
                    isError = true
                )
            } else {
                RoutedReply(
                    text = mapped.userMessage,
                    isOfflineEngine = false,
                    engineLabel = "Gemini Error",
                    isError = true
                )
            }
        }
    }

    private suspend fun executeOfflineOnly(
        prompt: String,
        history: List<ChatMessage>,
        onStreamChunk: suspend (String, Boolean, String) -> Unit
    ): RoutedReply {
        val sb = StringBuilder()
        return try {
            llamaStreamFn(prompt, history).collect { chunk ->
                sb.append(chunk)
                onStreamChunk(chunk, true, "Llama 3.2 • Offline")
            }
            val finalText = sb.toString().trim()
            if (finalText.isNotEmpty()) {
                RoutedReply(
                    text = finalText,
                    isOfflineEngine = true,
                    engineLabel = "Llama 3.2 • Offline",
                    isError = false
                )
            } else {
                RoutedReply(
                    text = "Local Llama generated no output. Try rephrasing your message.",
                    isOfflineEngine = true,
                    engineLabel = "Llama Offline Error",
                    isError = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val message = (t as? LocalLlamaException)?.message
                ?: t.message
                ?: "Offline Llama inference failed. Verify your downloaded GGUF model."
            RoutedReply(
                text = message,
                isOfflineEngine = true,
                engineLabel = "Llama Offline Error",
                isError = true
            )
        }
    }

    private suspend fun executeAuto(
        prompt: String,
        history: List<ChatMessage>,
        isOnline: Boolean,
        isVoice: Boolean,
        onStreamChunk: suspend (String, Boolean, String) -> Unit
    ): RoutedReply {
        var onlineErrorMsg: String? = null
        if (isOnline) {
            val onlineSb = StringBuilder()
            try {
                onlineStreamFn(prompt, history, isVoice).collect { chunk ->
                    onlineSb.append(chunk)
                    onStreamChunk(chunk, false, "Gemini • Online")
                }
                val finalOnline = onlineSb.toString().trim()
                if (finalOnline.isNotEmpty()) {
                    return RoutedReply(
                        text = finalOnline,
                        isOfflineEngine = false,
                        engineLabel = "Gemini • Online",
                        isError = false
                    )
                }
                onlineErrorMsg = "Gemini returned an empty response."
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val partial = onlineSb.toString().trim()
                val mapped = mapOnlineErrorFn(t)
                if (partial.isNotEmpty()) {
                    return RoutedReply(
                        text = "$partial\n\n[Interrupted: ${mapped.userMessage}]",
                        isOfflineEngine = false,
                        engineLabel = "Gemini • Partial",
                        isError = true
                    )
                }
                onlineErrorMsg = mapped.userMessage
            }
        }

        // Fall back to local GGUF ONLY if a real verified model is installed
        if (llamaReadyFn()) {
            val offlineSb = StringBuilder()
            val label = if (isOnline) "Llama 3.2 • Fallback" else "Llama 3.2 • Offline"
            try {
                llamaStreamFn(prompt, history).collect { chunk ->
                    offlineSb.append(chunk)
                    onStreamChunk(chunk, true, label)
                }
                val finalOffline = offlineSb.toString().trim()
                if (finalOffline.isNotEmpty()) {
                    return RoutedReply(
                        text = finalOffline,
                        isOfflineEngine = true,
                        engineLabel = label,
                        isError = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val llamaErr = t.message ?: "Local Llama inference failed."
                val combined = if (onlineErrorMsg != null) {
                    "Online Gemini failed ($onlineErrorMsg) and offline fallback failed ($llamaErr)."
                } else {
                    llamaErr
                }
                return RoutedReply(
                    text = combined,
                    isOfflineEngine = true,
                    engineLabel = "Assistant Error",
                    isError = true
                )
            }
        }

        val combinedError = if (onlineErrorMsg != null) {
            "$onlineErrorMsg\n\nNo offline Meta Llama 3.2 GGUF model is installed for offline fallback. Open Offline AI Brain & Models to download Llama 3.2 1B Instruct."
        } else {
            "You are offline and no Meta Llama 3.2 GGUF model is installed. Open Offline AI Brain & Models to download Meta Llama 3.2 1B Instruct (~770 MB)."
        }

        return RoutedReply(
            text = combinedError,
            isOfflineEngine = !isOnline,
            engineLabel = "Assistant Unavailable",
            isError = true
        )
    }
}
