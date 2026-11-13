package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.llamatik.library.platform.WhisperBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

private const val TAG = "SpeechManager"
private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
private const val SAMPLE_RATE_HZ = 16000

data class VoiceCapabilities(
    val isTtsInitialized: Boolean = false,
    val ttsEnginePackage: String = "",
    val availableHindiVoicesCount: Int = 0,
    val availableEnglishVoicesCount: Int = 0,
    val hasOfflineHindiVoice: Boolean = false,
    val hasOfflineEnglishVoice: Boolean = false,
    val hasMaleHindiVoice: Boolean = false,
    val hasFemaleHindiVoice: Boolean = false,
    val hasMaleEnglishVoice: Boolean = false,
    val hasFemaleEnglishVoice: Boolean = false,
    val hasVerifiedWhisperModel: Boolean = false,
    val isWhisperNativeAvailable: Boolean = false,
    val offlineTtsStatus: String = "Checking Android TTS voices...",
    val offlineSttStatus: String = "Checking offline Whisper STT..."
)

data class PersonaAvailability(
    val available: Boolean,
    val supportsOffline: Boolean,
    val badgeText: String
)

interface WhisperNativeBackend {
    fun isNativeAvailable(): Boolean
    fun initModel(modelPath: String): Boolean
    fun transcribeWav(wavPath: String, language: String? = null, initialPrompt: String? = null): String
    fun release()
}

class LlamatikWhisperBackend : WhisperNativeBackend {
    @Volatile
    private var checked = false
    @Volatile
    private var available = false

    override fun isNativeAvailable(): Boolean {
        if (!checked) {
            synchronized(this) {
                if (!checked) {
                    available = try {
                        WhisperBridge.getModelPath("check")
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w(TAG, "Native whisper library unavailable on this ABI/runtime", e)
                        false
                    } catch (e: Throwable) {
                        Log.w(TAG, "Unexpected error checking whisper native library", e)
                        false
                    }
                    checked = true
                }
            }
        }
        return available
    }

    override fun initModel(modelPath: String): Boolean {
        if (!isNativeAvailable()) return false
        return try {
            WhisperBridge.initModel(modelPath)
        } catch (e: Throwable) {
            Log.e(TAG, "Whisper initModel failed", e)
            false
        }
    }

    override fun transcribeWav(wavPath: String, language: String?, initialPrompt: String?): String {
        if (!isNativeAvailable()) {
            throw IllegalStateException("Native Whisper engine is unavailable.")
        }
        return WhisperBridge.transcribeWav(wavPath, language, initialPrompt)
    }

    override fun release() {
        if (!isNativeAvailable()) return
        try {
            WhisperBridge.release()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Hybrid Voice Manager for Jarvis:
 * - Output (TTS): Uses Android TextToSpeech (probing real installed system/Google TTS voices for Hindi,
 *   Hinglish & English, reporting honest offline vs online voice availability).
 * - Input (STT):
 *   • Online mode: Android SpeechRecognizer (when Google/system recognition is available).
 *   • Offline mode: Local Whisper (whisper.cpp JNI via WhisperBridge) with verified ggml-base.bin
 *     and AudioRecord 16kHz PCM capture, never claiming offline STT without a verified model.
 * - Safe isolation: If TTS/STT hardware or engine fails, text chat continues unaffected.
 */
class SpeechManager(
    private val context: Context,
    private val modelManager: LocalLlamaModelManager? = null,
    private val whisperBackend: WhisperNativeBackend = LlamatikWhisperBackend()
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val whisperMutex = Mutex()
    private var offlineListenJob: Job? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var activeTtsEnginePackage = ""
    private var loadedWhisperModelPath: String? = null
    private var selectedVoiceRequiresNetwork = false

    private val utteranceCounter = AtomicInteger(0)
    private val activeUtteranceCount = AtomicInteger(0)

    // StateFlows
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _currentVoicePersona = MutableStateFlow(VoicePersona.DIYA_HINDI)
    val currentVoicePersona: StateFlow<VoicePersona> = _currentVoicePersona.asStateFlow()

    private val _isNaturalTtsActive = MutableStateFlow(false)
    val isNaturalTtsActive: StateFlow<Boolean> = _isNaturalTtsActive.asStateFlow()

    private val _isOfflineSpeechActive = MutableStateFlow(false)
    val isOfflineSpeechActive: StateFlow<Boolean> = _isOfflineSpeechActive.asStateFlow()

    private val _activeVoiceName = MutableStateFlow("Android System TTS")
    val activeVoiceName: StateFlow<String> = _activeVoiceName.asStateFlow()

    private val _capabilities = MutableStateFlow(VoiceCapabilities())
    val capabilities: StateFlow<VoiceCapabilities> = _capabilities.asStateFlow()

    private val _hasError = MutableStateFlow(false)
    val hasError: StateFlow<Boolean> = _hasError.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Callbacks
    var onSpeechComplete: ((String) -> Unit)? = null
    var onUserInterruption: (() -> Unit)? = null

    init {
        initAndroidTts()
        refreshCapabilities()
    }

    fun setOfflineMode(offline: Boolean) {
        _isOfflineSpeechActive.value = offline
        applyVoicePersona(_currentVoicePersona.value)
        refreshCapabilities()
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initAndroidTts() {
        try {
            activeTtsEnginePackage = GOOGLE_TTS_ENGINE
            tts = TextToSpeech(context.applicationContext, this, GOOGLE_TTS_ENGINE)
        } catch (e: Exception) {
            Log.w(TAG, "Google TTS engine binding failed, falling back to default Android TTS", e)
            initFallbackTts()
        }
    }

    private fun initFallbackTts() {
        try {
            activeTtsEnginePackage = "system_default"
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Android TextToSpeech", e)
            isTtsReady = false
            _isNaturalTtsActive.value = false
            refreshCapabilities()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            configureAudioAttributes()
            setupUtteranceListener()
            isTtsReady = true
            _isNaturalTtsActive.value = (activeTtsEnginePackage == GOOGLE_TTS_ENGINE)
            applyVoicePersona(_currentVoicePersona.value)
            refreshCapabilities()
            Log.d(TAG, "Android TTS initialized ($activeTtsEnginePackage)")
        } else {
            Log.w(TAG, "TTS init failed (status=$status) for $activeTtsEnginePackage")
            if (activeTtsEnginePackage == GOOGLE_TTS_ENGINE) {
                initFallbackTts()
            } else {
                isTtsReady = false
                _isNaturalTtsActive.value = false
                refreshCapabilities()
            }
        }
    }

    fun refreshCapabilities() {
        val voices = try {
            if (isTtsReady) tts?.voices.orEmpty() else emptySet()
        } catch (_: Exception) {
            emptySet()
        }

        val hindiVoices = voices.filter { it.locale.language.equals("hi", ignoreCase = true) }
        val englishVoices = voices.filter { it.locale.language.equals("en", ignoreCase = true) }

        val offlineHindi = hindiVoices.any { isVoiceInstalledOffline(it) }
        val offlineEnglish = englishVoices.any { isVoiceInstalledOffline(it) }

        val femaleHindi = hindiVoices.any { isFemaleVoice(it) }
        val maleHindi = hindiVoices.any { isMaleVoice(it) }
        val femaleEnglish = englishVoices.any { isFemaleVoice(it) }
        val maleEnglish = englishVoices.any { isMaleVoice(it) }

        val hasWhisperFile = modelManager?.getInstalledWhisperModelFile() != null
        val isWhisperNative = whisperBackend.isNativeAvailable()

        val ttsStatus = when {
            !isTtsReady -> "Android TTS unavailable on this device (text chat still works)."
            offlineHindi -> "Offline Hindi TTS ready (${hindiVoices.count { isVoiceInstalledOffline(it) }} local voice(s))."
            hindiVoices.isNotEmpty() -> "Hindi TTS installed (network voice only; install offline voice pack in Android TTS settings)."
            else -> "No Hindi TTS voice installed on this device. English/fallback TTS active."
        }

        val sttStatus = when {
            hasWhisperFile && isWhisperNative -> "Offline Whisper STT ready (ggml-base.bin verified)."
            hasWhisperFile && !isWhisperNative -> "Whisper model verified, but native libwhisper is unavailable on this ABI."
            else -> "Offline Whisper STT not installed. Download Whisper Base (141 MB) for offline mic input."
        }

        _capabilities.value = VoiceCapabilities(
            isTtsInitialized = isTtsReady,
            ttsEnginePackage = if (isTtsReady) activeTtsEnginePackage else "",
            availableHindiVoicesCount = hindiVoices.size,
            availableEnglishVoicesCount = englishVoices.size,
            hasOfflineHindiVoice = offlineHindi,
            hasOfflineEnglishVoice = offlineEnglish,
            hasMaleHindiVoice = maleHindi,
            hasFemaleHindiVoice = femaleHindi,
            hasMaleEnglishVoice = maleEnglish,
            hasFemaleEnglishVoice = femaleEnglish,
            hasVerifiedWhisperModel = hasWhisperFile,
            isWhisperNativeAvailable = isWhisperNative,
            offlineTtsStatus = ttsStatus,
            offlineSttStatus = sttStatus
        )
    }

    /**
     * Checks true on-device voice availability for a persona instead of faking missing voices.
     */
    fun getPersonaAvailability(persona: VoicePersona): PersonaAvailability {
        if (!isTtsReady) {
            return PersonaAvailability(
                available = false,
                supportsOffline = false,
                badgeText = "TTS Unavailable"
            )
        }

        val voices = try {
            tts?.voices.orEmpty()
        } catch (_: Exception) {
            emptySet()
        }

        if (voices.isEmpty()) {
            return PersonaAvailability(
                available = true,
                supportsOffline = false,
                badgeText = "System Default"
            )
        }

        val targetLang = persona.primaryLocale.language
        val langVoices = voices.filter { it.locale.language.equals(targetLang, ignoreCase = true) }
        if (langVoices.isEmpty()) {
            val fallbackLangVoices = voices.filter {
                it.locale.language.equals(persona.fallbackLocale.language, ignoreCase = true)
            }
            return if (fallbackLangVoices.isNotEmpty()) {
                PersonaAvailability(
                    available = true,
                    supportsOffline = fallbackLangVoices.any { isVoiceInstalledOffline(it) },
                    badgeText = "Fallback Locale"
                )
            } else {
                PersonaAvailability(
                    available = false,
                    supportsOffline = false,
                    badgeText = "Not Installed"
                )
            }
        }

        val genderMatched = when (persona.genderPreference) {
            "female", "indian_female" -> langVoices.filter { isFemaleVoice(it) }
            "male", "indian_male" -> langVoices.filter { isMaleVoice(it) }
            else -> langVoices
        }

        val candidate = if (genderMatched.isNotEmpty()) genderMatched else langVoices
        val hasExactGender = genderMatched.isNotEmpty()
        val offlineReady = candidate.any { isVoiceInstalledOffline(it) }

        val badge = when {
            hasExactGender && offlineReady -> "Offline Ready"
            hasExactGender -> "Online Voice"
            offlineReady -> "Alt Voice (Offline)"
            else -> "Alt Voice (Online)"
        }
        return PersonaAvailability(
            available = true,
            supportsOffline = offlineReady,
            badgeText = badge
        )
    }

    fun openTtsInstallSettings(): Boolean {
        return try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot launch TTS data installer", e)
            false
        }
    }

    private fun configureAudioAttributes() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set AudioAttributes on TTS", e)
        }
    }

    private fun setupUtteranceListener() {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    val remaining = activeUtteranceCount.decrementAndGet()
                    if (remaining <= 0) {
                        activeUtteranceCount.set(0)
                        _isSpeaking.value = false
                    }
                }

                override fun onError(utteranceId: String?) {
                    val remaining = activeUtteranceCount.decrementAndGet()
                    if (remaining <= 0) {
                        activeUtteranceCount.set(0)
                        _isSpeaking.value = false
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach UtteranceProgressListener", e)
        }
    }

    fun setVoicePersona(persona: VoicePersona) {
        _currentVoicePersona.value = persona
        applyVoicePersona(persona)
    }

    private fun applyVoicePersona(persona: VoicePersona, isHindiText: Boolean = false) {
        if (!isTtsReady || tts == null) return
        try {
            tts?.setPitch(persona.pitch)
            tts?.setSpeechRate(persona.speechRate * _speechRate.value)

            val wantsHindi = isHindiText || persona.id.contains("hindi")
            val targetLocale = if (wantsHindi) Locale("hi", "IN") else persona.primaryLocale

            val langResult = tts?.setLanguage(targetLocale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val inFallback = tts?.setLanguage(Locale("en", "IN"))
                if (inFallback == TextToSpeech.LANG_MISSING_DATA || inFallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(persona.fallbackLocale)
                }
            }

            val availableVoices = tts?.voices
            if (!availableVoices.isNullOrEmpty()) {
                val bestVoice = selectBestInstalledVoice(availableVoices, persona, isHindiText)
                if (bestVoice != null) {
                    tts?.voice = bestVoice
                    selectedVoiceRequiresNetwork = bestVoice.isNetworkConnectionRequired
                    _activeVoiceName.value = bestVoice.name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply voice persona ${persona.displayName}", e)
        }
    }

    private fun selectBestInstalledVoice(
        voices: Set<Voice>,
        persona: VoicePersona,
        isHindiText: Boolean
    ): Voice? {
        val wantsHindi = isHindiText || persona.id.contains("hindi")
        val targetLang = if (wantsHindi) "hi" else persona.primaryLocale.language
        val targetCountry = if (wantsHindi) "IN" else persona.primaryLocale.country
        val isOffline = _isOfflineSpeechActive.value

        val matchingLangVoices = voices.filter { it.locale.language.equals(targetLang, ignoreCase = true) }
        val pool = when {
            matchingLangVoices.isNotEmpty() -> matchingLangVoices
            wantsHindi -> {
                val inVoices = voices.filter { it.locale.country.equals("IN", ignoreCase = true) }
                if (inVoices.isNotEmpty()) inVoices else voices
            }
            else -> {
                val fb = voices.filter { it.locale.language.equals(persona.fallbackLocale.language, ignoreCase = true) }
                if (fb.isNotEmpty()) fb else voices
            }
        }

        // In offline mode, avoid voices requiring network or missing download data
        val filteredPool = if (isOffline) {
            val offlineOnly = pool.filter { isVoiceInstalledOffline(it) }
            if (offlineOnly.isNotEmpty()) offlineOnly else pool
        } else {
            pool
        }

        return filteredPool.sortedWith(
            compareByDescending<Voice> { v ->
                val name = v.name.lowercase()
                var score = 0

                if (wantsHindi) {
                    if (v.locale.language.equals("hi", ignoreCase = true)) score += 600
                    if (v.locale.country.equals("IN", ignoreCase = true)) score += 300
                    if (v.locale.country.equals("US", ignoreCase = true) || v.locale.country.equals("GB", ignoreCase = true)) {
                        score -= 800
                    }
                } else if (persona.voiceSubtagHint.isNotEmpty() && name.contains(persona.voiceSubtagHint)) {
                    score += 200
                }

                if (isOffline) {
                    if (isVoiceInstalledOffline(v)) score += 400 else score -= 300
                } else {
                    if (name.contains("journey") || name.contains("studio")) score += 80
                    if (name.contains("network") || name.contains("wavenet") || name.contains("neural")) score += 60
                }

                if (v.quality == Voice.QUALITY_VERY_HIGH) score += 35
                if (v.quality == Voice.QUALITY_HIGH) score += 20

                when (persona.genderPreference) {
                    "female", "indian_female" -> if (isFemaleVoice(v)) score += 60
                    "male", "indian_male" -> if (isMaleVoice(v)) score += 60
                }

                if (targetCountry.isNotEmpty() && v.locale.country.equals(targetCountry, ignoreCase = true)) {
                    score += 15
                }

                score
            }
        ).firstOrNull()
    }

    private fun isVoiceInstalledOffline(voice: Voice): Boolean {
        val features = voice.features
        val missingData = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
        return !voice.isNetworkConnectionRequired && !missingData
    }

    private fun isFemaleVoice(voice: Voice): Boolean {
        val name = voice.name.lowercase()
        return name.contains("female") || name.contains("woman") || name.contains("f0") ||
            name.contains("hie") || name.contains("cfh") || name.contains("cfl") ||
            name.contains("end") || name.contains("ene") || name.contains("sfg") ||
            name.contains("tpd") || name.contains("ioc") || name.contains("ioe")
    }

    private fun isMaleVoice(voice: Voice): Boolean {
        val name = voice.name.lowercase()
        return name.contains("male") || name.contains("man") || name.contains("m0") ||
            name.contains("hid") || name.contains("enc") || name.contains("rjs") ||
            name.contains("iom") || name.contains("ioh") || name.contains("sfc") ||
            name.contains("tpb")
    }

    fun previewVoice(persona: VoicePersona) {
        stopSpeaking()
        setVoicePersona(persona)
        speak(persona.sampleText, "preview_${persona.id}")
    }

    fun setSpeechRate(rate: Float) {
        _speechRate.value = rate
        applyVoicePersona(_currentVoicePersona.value)
    }

    fun toggleMute(): Boolean {
        _isMuted.value = !_isMuted.value
        if (_isMuted.value) stopSpeaking()
        return _isMuted.value
    }

    fun toggleMicMute(): Boolean {
        _isMicMuted.value = !_isMicMuted.value
        if (_isMicMuted.value) stopListening()
        return _isMicMuted.value
    }

    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
        if (muted) stopListening()
    }

    fun clearError() {
        _hasError.value = false
        _errorMessage.value = null
    }

    fun clearRecognizedText() {
        _recognizedText.value = ""
    }

    fun isHindiOrHinglish(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.any { it in '\u0900'..'\u097F' }) return true
        val lower = text.lowercase()
        val hindiKeywords = listOf(
            "namaste", "kya", "kyun", "kaise", "kaisa", "kaisi", "aap", "tum", "hum", "mera", "meri", "mere",
            "tera", "teri", "tere", "mujhe", "tujhe", "humein", "batao", "bataiye", "hai", "hain", "ho",
            "hoon", "nahi", "nahin", "tha", "thi", "the", "karo", "kariye", "karna", "karta", "karti",
            "accha", "achha", "theek", "shukriya", "dhanyawad", "haan", "bhai", "suno", "sunie", "bolo",
            "boliye", "aaj", "kal", "yahan", "wahan", "kahan", "kaun", "kitna", "kitni", "chahiye", "madad"
        )
        return hindiKeywords.any { kw -> Regex("""\b$kw\b""").containsMatchIn(lower) }
    }

    fun speakStreamingChunk(text: String, isFirstChunk: Boolean = false) {
        if (!isTtsReady || tts == null || text.isBlank() || _isMuted.value) return
        try {
            val clean = cleanTextForNaturalSpeech(text)
            if (clean.isBlank()) return

            val containsHindi = isHindiOrHinglish(clean)
            applyVoicePersona(_currentVoicePersona.value, isHindiText = containsHindi)

            val utteranceId = "stream_chunk_${utteranceCounter.incrementAndGet()}"
            val queueMode = if (isFirstChunk) {
                activeUtteranceCount.set(1)
                TextToSpeech.QUEUE_FLUSH
            } else {
                activeUtteranceCount.incrementAndGet()
                TextToSpeech.QUEUE_ADD
            }

            val speechBundle = buildSpeechBundle()
            _isSpeaking.value = true
            tts?.speak(clean, queueMode, speechBundle, utteranceId)
        } catch (e: Exception) {
            Log.w(TAG, "speakStreamingChunk failed cleanly: ${e.message}")
            _isSpeaking.value = false
        }
    }

    fun speak(text: String, utteranceId: String = "jarvis_reply") {
        if (!isTtsReady || tts == null || text.isBlank() || _isMuted.value) return
        stopSpeaking()
        clearError()

        try {
            val clean = cleanTextForNaturalSpeech(text)
            if (clean.isBlank()) return

            val containsHindi = isHindiOrHinglish(clean)
            applyVoicePersona(_currentVoicePersona.value, isHindiText = containsHindi)

            activeUtteranceCount.set(1)
            _isSpeaking.value = true
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, buildSpeechBundle(), utteranceId)
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed cleanly: ${e.message}")
            activeUtteranceCount.set(0)
            _isSpeaking.value = false
        }
    }

    private fun buildSpeechBundle(): Bundle {
        return Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            if (!_isOfflineSpeechActive.value && selectedVoiceRequiresNetwork) {
                putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true")
            }
        }
    }

    private fun cleanTextForNaturalSpeech(input: String): String {
        return input
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""`[^`]*`"""), "")
            .replace(Regex("""[*#_~\[\]\(\)<>]"""), "")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""[\p{So}\p{Cn}\uD83C-\uDBFF\uDC00-\uDFFF]"""), "")
            .replace(Regex("""\bAI\b"""), "A.I.")
            .replace(Regex("""\be\.g\.""", RegexOption.IGNORE_CASE), "for example")
            .replace(Regex("""\bi\.e\.""", RegexOption.IGNORE_CASE), "that is")
            .replace(Regex("""\bapprox\.""", RegexOption.IGNORE_CASE), "approximately")
            .replace(Regex("""[-•*]\s+"""), ", ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        activeUtteranceCount.set(0)
        _isSpeaking.value = false
    }

    /**
     * Starts speech capture:
     * - Offline mode: uses local Whisper (whisper.cpp) if verified ggml-base.bin is installed;
     *   otherwise reports missing offline STT honestly instead of silently hanging.
     * - Online mode: uses Android SpeechRecognizer.
     */
    fun startListening(onResult: (String) -> Unit) {
        if (_isMicMuted.value) {
            Log.d(TAG, "Microphone is muted; ignoring startListening.")
            return
        }
        if (!hasRecordAudioPermission()) {
            _hasError.value = true
            _errorMessage.value = "Microphone permission required. Please allow access."
            return
        }

        stopListening()
        clearError()
        this.onSpeechComplete = onResult
        _recognizedText.value = ""

        val whisperModelFile = modelManager?.getInstalledWhisperModelFile()
        if (_isOfflineSpeechActive.value) {
            if (whisperModelFile != null && whisperBackend.isNativeAvailable()) {
                startOfflineWhisperListening(whisperModelFile)
                return
            } else {
                _hasError.value = true
                _isListening.value = false
                _errorMessage.value = if (whisperModelFile == null) {
                    "Offline Whisper STT model not installed. Download Whisper Base (141 MB) in Offline AI Brain & Models."
                } else {
                    "Native Whisper library unavailable on this runtime. Type your message instead."
                }
                return
            }
        }

        startPlatformSpeechRecognizer()
    }

    @SuppressLint("MissingPermission")
    private fun startOfflineWhisperListening(whisperModelFile: File) {
        _isListening.value = true
        offlineListenJob = speechScope.launch {
            var recorder: AudioRecord? = null
            var tempWav: File? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    throw IllegalStateException("AudioRecord buffer unavailable on this device.")
                }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, SAMPLE_RATE_HZ)
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone hardware could not initialize for offline recording.")
                }

                recorder.startRecording()
                val pcmStream = ByteArrayOutputStream()
                val readBuffer = ShortArray(1024)
                val startTimeMs = System.currentTimeMillis()
                var speechDetected = false
                var lastVoiceMs = startTimeMs

                while (isActive) {
                    val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                    if (readCount <= 0) continue

                    var sumSquares = 0.0
                    val byteBuf = ByteBuffer.allocate(readCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until readCount) {
                        val s = readBuffer[i]
                        byteBuf.putShort(s)
                        sumSquares += (s * s).toDouble()
                    }
                    val rms = sqrt(sumSquares / readCount).toFloat()
                    val normalizedRms = (rms / 6000f).coerceIn(0.05f, 1f)
                    _rmsLevel.value = normalizedRms

                    if (rms > 650f) {
                        if (!speechDetected && _isSpeaking.value) {
                            stopSpeaking()
                            onUserInterruption?.invoke()
                        }
                        speechDetected = true
                        lastVoiceMs = System.currentTimeMillis()
                    }

                    pcmStream.write(byteBuf.array())

                    val now = System.currentTimeMillis()
                    if (!speechDetected && now - startTimeMs > 5500L) break
                    if (speechDetected && now - lastVoiceMs > 1400L) break
                    if (now - startTimeMs > 12000L) break
                }

                try {
                    recorder.stop()
                } catch (_: Exception) {
                }

                _isListening.value = false
                _rmsLevel.value = 0f

                val pcmBytes = pcmStream.toByteArray()
                if (!speechDetected || pcmBytes.size < SAMPLE_RATE_HZ) {
                    _hasError.value = true
                    _errorMessage.value = "Didn't catch that. Tap orb to speak again."
                    return@launch
                }

                tempWav = File.createTempFile("jarvis_whisper_", ".wav", context.cacheDir)
                writeWavFile(tempWav, pcmBytes, SAMPLE_RATE_HZ)

                val transcript = whisperMutex.withLock {
                    if (loadedWhisperModelPath != whisperModelFile.absolutePath) {
                        val loaded = whisperBackend.initModel(whisperModelFile.absolutePath)
                        if (!loaded) {
                            throw IllegalStateException("Whisper model initialization failed.")
                        }
                        loadedWhisperModelPath = whisperModelFile.absolutePath
                    }
                    whisperBackend.transcribeWav(
                        wavPath = tempWav.absolutePath,
                        language = null,
                        initialPrompt = "नमस्ते, Jarvis."
                    ).trim()
                }

                if (transcript.isNotBlank() && !transcript.equals("[BLANK_AUDIO]", ignoreCase = true)) {
                    _recognizedText.value = transcript
                    clearError()
                    withContext(Dispatchers.Main) {
                        onSpeechComplete?.invoke(transcript)
                    }
                } else {
                    _hasError.value = true
                    _errorMessage.value = "Didn't catch clear speech. Tap orb to try again."
                }
            } catch (e: CancellationException) {
                _isListening.value = false
                _rmsLevel.value = 0f
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Offline Whisper listening failed", e)
                _isListening.value = false
                _rmsLevel.value = 0f
                _hasError.value = true
                _errorMessage.value = e.message ?: "Offline speech recognition failed."
            } finally {
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
                tempWav?.takeIf { it.exists() }?.delete()
            }
        }
    }

    private fun writeWavFile(target: File, pcmData: ByteArray, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmData.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmData.size)
        }.array()

        FileOutputStream(target).use { out ->
            out.write(header)
            out.write(pcmData)
        }
    }

    private fun startPlatformSpeechRecognizer() {
        _isListening.value = true
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _isListening.value = false
                    _hasError.value = true
                    _errorMessage.value = "Speech recognition service unavailable on this device."
                    return@post
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                        }

                        override fun onBeginningOfSpeech() {
                            _isListening.value = true
                            if (_isSpeaking.value) {
                                stopSpeaking()
                                onUserInterruption?.invoke()
                            }
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            val level = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                            _rmsLevel.value = level
                            if (level > 0.40f && _isSpeaking.value) {
                                stopSpeaking()
                                onUserInterruption?.invoke()
                            }
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _isListening.value = false
                            _rmsLevel.value = 0f
                        }

                        override fun onError(error: Int) {
                            _isListening.value = false
                            _rmsLevel.value = 0f
                            _hasError.value = true
                            _errorMessage.value = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap orb to speak."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network speech service unavailable."
                                else -> "Speech recognition error ($error). Tap orb to retry."
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            _rmsLevel.value = 0f
                            clearError()
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty().trim()
                            if (text.isNotBlank()) {
                                _recognizedText.value = text
                                onSpeechComplete?.invoke(text)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty().trim()
                            if (text.isNotBlank()) {
                                _recognizedText.value = text
                                if (_isSpeaking.value) {
                                    stopSpeaking()
                                    onUserInterruption?.invoke()
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "hi", "en-US"))
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "साफ़ हिंदी या इंग्लिश में बोलिए...")
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _isListening.value = false
                _hasError.value = true
                _errorMessage.value = "Could not start mic: ${e.localizedMessage}"
                Log.e(TAG, "Failed to start SpeechRecognizer", e)
            }
        }
    }

    fun onExternalVoiceResult(text: String) {
        val clean = text.trim()
        if (clean.isNotBlank()) {
            if (_isSpeaking.value) {
                stopSpeaking()
                onUserInterruption?.invoke()
            }
            _recognizedText.value = clean
            _isListening.value = false
            _rmsLevel.value = 0f
            clearError()
            onSpeechComplete?.invoke(clean)
        }
    }

    fun stopListening() {
        offlineListenJob?.cancel()
        offlineListenJob = null
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null
            _isListening.value = false
            _rmsLevel.value = 0f
        }
    }

    fun release() {
        stopListening()
        stopSpeaking()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        loadedWhisperModelPath = null
        whisperBackend.release()
    }
}
