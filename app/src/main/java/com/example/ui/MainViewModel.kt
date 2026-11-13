package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.model.AssistantMode
import com.example.data.model.ChatMessage
import com.example.data.model.ContactItem
import com.example.data.model.InstalledAppItem
import com.example.service.BiometricLockManager
import com.example.service.DeviceAssistantManager
import com.example.service.GeminiLiveVoiceEngine
import com.example.service.HybridAssistantRouter
import com.example.service.LiveConnectionState
import com.example.service.LocalLlamaInferenceEngine
import com.example.service.LocalLlamaModelManager
import com.example.service.OfflineAssistantEngine
import com.example.service.OnlineGeminiEngine
import com.example.service.SpeechManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AppScreenMode {
    VOICE,
    CHAT
}

private const val TAG = "MainViewModel"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var activeChatJob: Job? = null
    private var activeRequestId: String? = null

    private val db = AppDatabase.getInstance(application)
    private val chatDao = db.chatDao()

    val deviceManager = DeviceAssistantManager(application)
    val offlineEngine = OfflineAssistantEngine(deviceManager)
    val onlineEngine = OnlineGeminiEngine()
    val llamaModelManager = LocalLlamaModelManager(application)
    val llamaEngine = LocalLlamaInferenceEngine(application, llamaModelManager)
    val speechManager = SpeechManager(application, llamaModelManager)
    val geminiLiveEngine = GeminiLiveVoiceEngine(
        context = application,
        isNetworkOnline = { deviceManager.isOnline() }
    )
    val biometricLockManager = BiometricLockManager(application)

    private var router = HybridAssistantRouter(onlineEngine, llamaEngine, offlineEngine)

    private val _isModelManagerOpen = MutableStateFlow(false)
    val isModelManagerOpen: StateFlow<Boolean> = _isModelManagerOpen.asStateFlow()

    private val _modelTestStatus = MutableStateFlow<String?>(null)
    val modelTestStatus: StateFlow<String?> = _modelTestStatus.asStateFlow()

    fun setModelManagerOpen(open: Boolean) {
        _isModelManagerOpen.value = open
        if (!open) {
            _modelTestStatus.value = null
        } else {
            speechManager.refreshCapabilities()
        }
    }

    // Active session
    private val _currentSessionId = MutableStateFlow("session_main")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    // Sessions list
    val sessions = chatDao.getAllSessions().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    // Streaming message bubble
    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    // Observable messages for current session
    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    // Selected image URI for multimodal prompt
    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    // Mode: Auto / Online / Offline
    private val _assistantMode = MutableStateFlow(AssistantMode.AUTO)
    val assistantMode: StateFlow<AssistantMode> = _assistantMode.asStateFlow()

    // Dark Mode: default false
    private val _isDarkMode = MutableStateFlow<Boolean?>(false)
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    // UI Sheets / Modals
    private val _isVoiceModalOpen = MutableStateFlow(false)
    val isVoiceModalOpen: StateFlow<Boolean> = _isVoiceModalOpen.asStateFlow()

    private val _isContactsSheetOpen = MutableStateFlow(false)
    val isContactsSheetOpen: StateFlow<Boolean> = _isContactsSheetOpen.asStateFlow()

    private val _isAppsSheetOpen = MutableStateFlow(false)
    val isAppsSheetOpen: StateFlow<Boolean> = _isAppsSheetOpen.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _currentScreenMode = MutableStateFlow(AppScreenMode.CHAT)
    val currentScreenMode: StateFlow<AppScreenMode> = _currentScreenMode.asStateFlow()

    private val _pendingEditText = MutableStateFlow<String?>(null)
    val pendingEditText: StateFlow<String?> = _pendingEditText.asStateFlow()

    fun setPendingEditText(text: String?) {
        _pendingEditText.value = text
    }

    fun setScreenMode(mode: AppScreenMode) {
        if (mode == AppScreenMode.CHAT && _currentScreenMode.value == AppScreenMode.VOICE) {
            stopLiveVoiceSession()
        }
        _currentScreenMode.value = mode
    }

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactItem>>(emptyList())
    val contacts: StateFlow<List<ContactItem>> = _contacts.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    init {
        initSession()
        observeCurrentMessages()
        refreshContacts()
        refreshApps()

        speechManager.onUserInterruption = {
            stopGeneration()
            Log.d(TAG, "Voice interaction cancelled due to user interruption (barge-in)")
        }

        geminiLiveEngine.onTurnCommitted = { isUser, text ->
            saveLiveTurnToHistory(isUser, text)
        }

        viewModelScope.launch {
            combine(deviceManager.isOnlineFlow, _assistantMode) { isOnline, mode ->
                when (mode) {
                    AssistantMode.ONLINE -> false
                    AssistantMode.OFFLINE -> true
                    AssistantMode.AUTO -> !isOnline
                }
            }.collect { useOffline ->
                speechManager.setOfflineMode(useOffline)
            }
        }

        viewModelScope.launch {
            llamaModelManager.modelStates.collect {
                speechManager.refreshCapabilities()
            }
        }
    }

    private fun saveLiveTurnToHistory(isUser: Boolean, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val sessionId = _currentSessionId.value
        val msg = ChatMessage(
            sessionId = sessionId,
            text = clean,
            isUser = isUser,
            isVoice = true,
            isOfflineEngine = false,
            engineLabel = if (isUser) "" else "Gemini • Live Voice"
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.insertSessionIfAbsent(
                    ChatSessionEntity(
                        sessionId = sessionId,
                        title = "New Chat"
                    )
                )
                chatDao.insertMessage(ChatMessageEntity.fromDomain(msg))
                if (isUser) {
                    chatDao.updateSessionMeta(
                        sessionId = sessionId,
                        title = clean.take(24),
                        lastModified = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving Live Voice turn to history", e)
            }
        }
    }

    fun startLiveVoiceSession() {
        speechManager.stopListening()
        speechManager.stopSpeaking()
        geminiLiveEngine.startSession(
            assistantMode = _assistantMode.value,
            persona = speechManager.currentVoicePersona.value,
            recentHistory = _currentMessages.value
        )
    }

    fun reconnectLiveVoiceSession() {
        speechManager.stopListening()
        speechManager.stopSpeaking()
        geminiLiveEngine.reconnect(
            assistantMode = _assistantMode.value,
            persona = speechManager.currentVoicePersona.value,
            recentHistory = _currentMessages.value
        )
    }

    fun stopLiveVoiceSession() {
        geminiLiveEngine.stopSession()
    }

    fun sendLiveVoiceTextMessage(promptText: String) {
        val text = promptText.trim()
        val imageUri = _selectedImageUri.value
        if (text.isBlank() && imageUri == null) return

        val liveState = geminiLiveEngine.connectionState.value
        val isLiveConnected = liveState == LiveConnectionState.CONNECTED ||
            liveState == LiveConnectionState.LISTENING ||
            liveState == LiveConnectionState.SPEAKING

        if (isLiveConnected) {
            _selectedImageUri.value = null
            val effectivePrompt = text.ifBlank { "Analyze attached image" }
            val sessionId = _currentSessionId.value
            val userMsg = ChatMessage(
                sessionId = sessionId,
                text = effectivePrompt,
                isUser = true,
                imageUri = imageUri?.toString(),
                isVoice = true
            )
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    chatDao.insertSessionIfAbsent(ChatSessionEntity(sessionId = sessionId, title = "New Chat"))
                    chatDao.insertMessage(ChatMessageEntity.fromDomain(userMsg))
                    chatDao.updateSessionMeta(
                        sessionId = sessionId,
                        title = effectivePrompt.take(24),
                        lastModified = System.currentTimeMillis()
                    )
                }
                val bitmap = if (imageUri != null) {
                    withContext(Dispatchers.IO) { loadBitmapFromUri(imageUri) }
                } else {
                    null
                }
                val sent = geminiLiveEngine.sendTypedMessage(effectivePrompt, bitmap)
                if (!sent) {
                    sendMessage(effectivePrompt, isVoice = false)
                }
            }
        } else {
            // If Live isn't connected (e.g. Offline mode), route through normal text engine cleanly
            sendMessage(text, isVoice = false)
        }
    }

    internal fun setRouterForTesting(testRouter: HybridAssistantRouter) {
        router = testRouter
    }

    private fun initSession() {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.insertSessionIfAbsent(
                ChatSessionEntity(
                    sessionId = _currentSessionId.value,
                    title = "New Chat"
                )
            )
        }
    }

    private fun observeCurrentMessages() {
        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                chatDao.getMessagesForSession(sessionId).collect { entityList ->
                    _currentMessages.value = entityList.map { it.toDomain() }
                }
            }
        }
    }

    fun setAssistantMode(mode: AssistantMode) {
        _assistantMode.value = mode
        val useOffline = when (mode) {
            AssistantMode.ONLINE -> false
            AssistantMode.OFFLINE -> true
            AssistantMode.AUTO -> !deviceManager.isOnline()
        }
        speechManager.setOfflineMode(useOffline)
        if (mode == AssistantMode.OFFLINE) {
            geminiLiveEngine.stopForOfflineMode()
        }
    }

    fun testLoadLocalModel(modelId: String) {
        viewModelScope.launch {
            _modelTestStatus.value = "Verifying and loading $modelId in llama.cpp..."
            val result = llamaEngine.ensureModelLoaded(modelId)
            _modelTestStatus.value = result.fold(
                onSuccess = { file -> "Load verified: ${file.name} initialized cleanly in llama.cpp." },
                onFailure = { err -> "Load failed: ${err.message}" }
            )
        }
    }

    fun stopGeneration() {
        activeRequestId = null
        activeChatJob?.cancel()
        activeChatJob = null
        llamaEngine.cancelGeneration()
        _streamingMessage.value = null
        _isGenerating.value = false
        speechManager.stopSpeaking()
    }

    fun toggleDarkMode() {
        _isDarkMode.value = when (_isDarkMode.value) {
            null -> true
            true -> false
            false -> null
        }
    }

    fun setSelectedImage(uri: Uri?) {
        _selectedImageUri.value = uri
    }

    fun setVoiceModalOpen(open: Boolean) {
        _isVoiceModalOpen.value = open
        if (!open) {
            speechManager.stopListening()
            speechManager.stopSpeaking()
        }
    }

    fun setContactsSheetOpen(open: Boolean) {
        _isContactsSheetOpen.value = open
        if (open) refreshContacts()
    }

    fun setAppsSheetOpen(open: Boolean) {
        _isAppsSheetOpen.value = open
        if (open) refreshApps()
    }

    fun setSettingsOpen(open: Boolean) {
        _isSettingsOpen.value = open
    }

    fun unlockApp() {
        biometricLockManager.isLocked = false
        _isLocked.value = false
    }

    fun lockApp() {
        biometricLockManager.isLocked = true
        _isLocked.value = true
    }

    fun toggleBiometricEnabled(enabled: Boolean) {
        biometricLockManager.isBiometricEnabled = enabled
        if (!enabled) {
            _isLocked.value = false
        }
    }

    fun refreshContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            _contacts.value = deviceManager.getContacts()
        }
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = deviceManager.getInstalledApps()
        }
    }

    fun createNewSession() {
        val newId = "session_" + UUID.randomUUID().toString().take(8)
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.insertSession(
                ChatSessionEntity(
                    sessionId = newId,
                    title = "New Chat",
                    createdAt = System.currentTimeMillis(),
                    lastModified = System.currentTimeMillis()
                )
            )
            _currentSessionId.value = newId
        }
    }

    fun selectSession(sessionId: String) {
        _currentSessionId.value = sessionId
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.deleteSession(sessionId)
            chatDao.clearMessagesForSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                createNewSession()
            }
        }
    }

    fun clearCurrentMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.clearMessagesForSession(_currentSessionId.value)
        }
    }

    fun sendMessage(promptText: String, isVoice: Boolean = false) {
        val text = promptText.trim()
        val imageUri = _selectedImageUri.value
        if (text.isBlank() && imageUri == null) return

        val previousJob = activeChatJob
        previousJob?.cancel()
        llamaEngine.cancelGeneration()

        if (isVoice) {
            speechManager.stopSpeaking()
        }
        speechManager.clearRecognizedText()
        speechManager.clearError()

        val sessionId = _currentSessionId.value
        val modeSnapshot = _assistantMode.value
        val historySnapshot = _currentMessages.value
        _selectedImageUri.value = null

        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        _streamingMessage.value = ChatMessage(
            sessionId = sessionId,
            text = "",
            isUser = false,
            isOfflineEngine = (modeSnapshot == AssistantMode.OFFLINE),
            isStreaming = true
        )
        _isGenerating.value = true

        activeChatJob = viewModelScope.launch {
            try {
                // Wait for any cancelled previous turn cleanup so streams and DB writes never interleave
                previousJob?.join()
                if (activeRequestId != requestId) return@launch

                val userMsg = ChatMessage(
                    sessionId = sessionId,
                    text = text.ifBlank { "Analyze attached image" },
                    isUser = true,
                    imageUri = imageUri?.toString(),
                    isVoice = isVoice
                )

                withContext(Dispatchers.IO) {
                    chatDao.insertSessionIfAbsent(
                        ChatSessionEntity(
                            sessionId = sessionId,
                            title = "New Chat"
                        )
                    )
                    chatDao.insertMessage(ChatMessageEntity.fromDomain(userMsg))
                    val preview = text.take(24).ifBlank { "Photo Query" }
                    chatDao.updateSessionMeta(
                        sessionId = sessionId,
                        title = preview,
                        lastModified = System.currentTimeMillis()
                    )
                }

                val bitmap = if (imageUri != null) {
                    withContext(Dispatchers.IO) { loadBitmapFromUri(imageUri) }
                } else {
                    null
                }

                val streamAccumulated = StringBuilder()
                val ttsSentenceBuffer = StringBuilder()
                var spokenChunksCount = 0
                var lastStreamingEngineOffline: Boolean? = null

                val reply = router.routeMessage(
                    prompt = text.ifBlank { "Analyze attached image" },
                    history = historySnapshot,
                    mode = modeSnapshot,
                    isOnline = deviceManager.isOnline(),
                    isVoice = isVoice,
                    bitmap = bitmap,
                    hasImageAttachment = (imageUri != null),
                    onStreamChunk = { chunk, isOffline, engineLabel ->
                        if (activeRequestId != requestId) return@routeMessage
                        if (lastStreamingEngineOffline != null && lastStreamingEngineOffline != isOffline) {
                            streamAccumulated.clear()
                            ttsSentenceBuffer.clear()
                        }
                        lastStreamingEngineOffline = isOffline
                        streamAccumulated.append(chunk)

                        if (isVoice) {
                            ttsSentenceBuffer.append(chunk)
                            val readySentences = extractSpeakableSentences(ttsSentenceBuffer)
                            for (sentence in readySentences) {
                                speechManager.speakStreamingChunk(
                                    sentence,
                                    isFirstChunk = (spokenChunksCount == 0)
                                )
                                spokenChunksCount++
                            }
                        }

                        _streamingMessage.value = ChatMessage(
                            sessionId = sessionId,
                            text = streamAccumulated.toString(),
                            isUser = false,
                            isOfflineEngine = isOffline,
                            engineLabel = engineLabel,
                            isStreaming = true
                        )
                    }
                )

                if (activeRequestId != requestId) return@launch

                if (isVoice && !reply.isError && ttsSentenceBuffer.isNotBlank()) {
                    val remaining = ttsSentenceBuffer.toString().trim()
                    if (remaining.isNotEmpty()) {
                        speechManager.speakStreamingChunk(
                            remaining,
                            isFirstChunk = (spokenChunksCount == 0)
                        )
                        spokenChunksCount++
                    }
                }

                _streamingMessage.value = null

                val botMsg = ChatMessage(
                    sessionId = sessionId,
                    text = reply.text,
                    isUser = false,
                    isOfflineEngine = reply.isOfflineEngine,
                    engineLabel = reply.engineLabel,
                    isStreaming = false,
                    isError = reply.isError
                )
                withContext(Dispatchers.IO) {
                    chatDao.insertMessage(ChatMessageEntity.fromDomain(botMsg))
                }

                if (isVoice && spokenChunksCount == 0 && !reply.isError) {
                    speechManager.speak(reply.text)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Chat generation cancelled cleanly")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in sendMessage", e)
                if (activeRequestId == requestId) {
                    val errMsg = ChatMessage(
                        sessionId = sessionId,
                        text = e.message ?: "Unexpected assistant error. Please try again.",
                        isUser = false,
                        isOfflineEngine = (modeSnapshot == AssistantMode.OFFLINE),
                        engineLabel = "Assistant Error",
                        isStreaming = false,
                        isError = true
                    )
                    try {
                        withContext(Dispatchers.IO) {
                            chatDao.insertMessage(ChatMessageEntity.fromDomain(errMsg))
                        }
                    } catch (dbEx: Exception) {
                        Log.e(TAG, "Failed saving error message to DB", dbEx)
                    }
                }
            } finally {
                if (activeRequestId == requestId) {
                    _streamingMessage.value = null
                    _isGenerating.value = false
                }
            }
        }
    }

    private fun extractSpeakableSentences(buffer: StringBuilder): List<String> {
        val sentenceDelimiters = charArrayOf('.', '!', '?', '\n', '।')
        val list = mutableListOf<String>()
        var idx = 0
        while (idx < buffer.length) {
            val c = buffer[idx]
            val isDelimiter = sentenceDelimiters.contains(c)
            val isCommaBreak = (c == ',' && buffer.substring(0, idx).split(" ").size >= 8)
            if (isDelimiter || isCommaBreak) {
                val chunk = buffer.substring(0, idx + 1).trim()
                if (chunk.isNotEmpty()) {
                    list.add(chunk)
                }
                buffer.delete(0, idx + 1)
                idx = 0
            } else {
                idx++
            }
        }
        return list
    }

    fun requestRealtimeUpdateNotification() {
        viewModelScope.launch {
            val updateText = if (deviceManager.isOnline()) {
                withContext(Dispatchers.IO) { onlineEngine.fetchRealtimeUpdates() }
            } else {
                "Offline: Device status is normal. Battery at ${deviceManager.getBatteryLevel()}%."
            }
            deviceManager.sendNotification(
                title = "Jarvis AI Update",
                message = updateText.take(120)
            )
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val contentResolver = getApplication<Application>().contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from uri", e)
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeChatJob?.cancel()
        llamaEngine.unloadModel()
        geminiLiveEngine.release()
        speechManager.release()
    }
}
