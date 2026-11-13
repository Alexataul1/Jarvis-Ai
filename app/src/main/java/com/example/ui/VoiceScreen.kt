package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import com.example.service.LiveConnectionState
import com.example.service.LiveErrorCategory
import com.example.service.VoicePersona
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.example.data.model.AssistantMode
import com.example.ui.components.AppsSheet
import com.example.ui.components.BiometricLockScreen
import com.example.ui.components.ChatDrawerContent
import com.example.ui.components.ContactsSheet
import com.example.ui.components.EnergyOrb
import com.example.ui.components.LocalModelManagerSheet
import com.example.ui.components.VoiceOrb
import com.example.ui.components.VoiceOrbState
import com.example.ui.theme.ChatGptBlack
import com.example.ui.theme.VoiceAccentGreen
import kotlinx.coroutines.launch

/**
 * ChatGPT Live Voice Mode Interface
 *
 * Implements the authentic ChatGPT Live Voice experience:
 * 1. Clean, minimalist background with large centered animated Voice Orb
 *    (soft blue, white, and light-violet cloudy gradients with real-time audio reactivity)
 * 2. Bottom horizontal control bar matching the ChatGPT Live Voice design:
 *    - [Plus +] and [Ask ChatGPT] pill-shaped text input (with photo/file attachment support)
 *    - Circular Microphone Mute/Unmute button (Mic/MicOff with real audio mute toggling)
 *    - Black circular [X] close button on the far right
 * 3. Top controls:
 *    - Circular Menu button at top-left opening full navigation drawer
 *    - Circular Settings button at top-right opening models/voice config
 *    - Clean online/offline status indicator (Gemini vs Meta Llama 3.2)
 * 4. Real conversation display, multimodal support, and seamless uninterrupted audio
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    viewModel: MainViewModel,
    onNavigateToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        viewModel.stopLiveVoiceSession()
        viewModel.speechManager.stopListening()
        viewModel.speechManager.stopSpeaking()
        onNavigateToChat()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Observe real-time Gemini Live engine states
    val liveState by viewModel.geminiLiveEngine.connectionState.collectAsState()
    val liveError by viewModel.geminiLiveEngine.error.collectAsState()
    val isMicMuted by viewModel.geminiLiveEngine.isMicMuted.collectAsState()
    val rmsLevel by viewModel.geminiLiveEngine.rmsLevel.collectAsState()
    val liveUserTranscript by viewModel.geminiLiveEngine.userTranscript.collectAsState()
    val liveAiTranscript by viewModel.geminiLiveEngine.aiTranscript.collectAsState()

    val isGenerating by viewModel.isGenerating.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isOnline by viewModel.deviceManager.isOnlineFlow.collectAsState()
    val assistantMode by viewModel.assistantMode.collectAsState()

    // Observe conversation & attachment states
    val currentMessages by viewModel.currentMessages.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val selectedImageUri by viewModel.selectedImageUri.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val currentVoicePersona by viewModel.speechManager.currentVoicePersona.collectAsState()
    val isLocked by viewModel.isLocked.collectAsState()

    // Observe device tools & sheets
    val contacts by viewModel.contacts.collectAsState()
    val apps by viewModel.installedApps.collectAsState()
    val isModelManagerOpen by viewModel.isModelManagerOpen.collectAsState()
    val isContactsSheetOpen by viewModel.isContactsSheetOpen.collectAsState()
    val isAppsSheetOpen by viewModel.isAppsSheetOpen.collectAsState()
    val modelStates by viewModel.llamaModelManager.modelStates.collectAsState()
    val activeLlamaId by viewModel.llamaModelManager.activeLlamaModelId.collectAsState()
    val voiceCapabilities by viewModel.speechManager.capabilities.collectAsState()
    val modelTestStatus by viewModel.modelTestStatus.collectAsState()
    var isAttachmentSheetOpen by remember { mutableStateOf(false) }

    // Text input state
    var inputText by remember { mutableStateOf("") }
    var orbStyle by remember { mutableStateOf("energy") }
    var isGeminiVoiceSheetOpen by remember { mutableStateOf(false) }

    // Activity result launcher for photo/attachment picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setSelectedImage(uri)
        }
    }

    // Permission launcher for RECORD_AUDIO
    val requestRecordAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startLiveVoiceSession()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for Live Voice conversation",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun startOrReconnectLiveVoice() {
        if (!viewModel.speechManager.hasRecordAudioPermission()) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.startLiveVoiceSession()
        }
    }

    // Start Gemini Live session automatically when entering VoiceScreen; clean up on exit
    LaunchedEffect(Unit) {
        startOrReconnectLiveVoice()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopLiveVoiceSession()
        }
    }

    val isListening = liveState == LiveConnectionState.LISTENING
    val isSpeaking = liveState == LiveConnectionState.SPEAKING
    val hasError = liveState == LiveConnectionState.ERROR

    // Dynamic orb state computation
    val orbState = when (liveState) {
        LiveConnectionState.ERROR -> VoiceOrbState.ERROR
        LiveConnectionState.LISTENING -> VoiceOrbState.LISTENING
        LiveConnectionState.SPEAKING -> VoiceOrbState.SPEAKING
        LiveConnectionState.CONNECTING -> VoiceOrbState.PROCESSING
        LiveConnectionState.CONNECTED,
        LiveConnectionState.DISCONNECTED -> VoiceOrbState.IDLE
    }

    val isRunningLive = liveState == LiveConnectionState.CONNECTED ||
        liveState == LiveConnectionState.LISTENING ||
        liveState == LiveConnectionState.SPEAKING

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(320.dp)
            ) {
                ChatDrawerContent(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    assistantMode = assistantMode,
                    currentVoicePersona = currentVoicePersona,
                    isNetworkConnected = isOnline,
                    isDarkMode = isDarkMode,
                    isBiometricEnabled = viewModel.biometricLockManager.isBiometricEnabled,
                    onSelectSession = { id ->
                        viewModel.selectSession(id)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        viewModel.createNewSession()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onDeleteSession = { id -> viewModel.deleteSession(id) },
                    onSetMode = { mode ->
                        viewModel.setAssistantMode(mode)
                        if (mode != AssistantMode.OFFLINE && !isRunningLive) {
                            startOrReconnectLiveVoice()
                        }
                    },
                    onSelectVoicePersona = { persona ->
                        viewModel.speechManager.setVoicePersona(persona)
                        if (isRunningLive) {
                            viewModel.reconnectLiveVoiceSession()
                        }
                    },
                    onPreviewVoicePersona = { persona -> viewModel.speechManager.previewVoice(persona) },
                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                    onToggleBiometric = { enable -> viewModel.toggleBiometricEnabled(enable) },
                    onLockNow = {
                        viewModel.lockApp()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenContacts = {
                        viewModel.setContactsSheetOpen(true)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenApps = {
                        viewModel.setAppsSheetOpen(true)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onTriggerNotification = {
                        viewModel.requestRealtimeUpdateNotification()
                        Toast.makeText(context, "AI briefing notification sent!", Toast.LENGTH_SHORT).show()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenModelManager = {
                        viewModel.setModelManagerOpen(true)
                        coroutineScope.launch { drawerState.close() }
                    },
                    voiceCapabilities = voiceCapabilities,
                    getPersonaAvailability = { persona -> viewModel.speechManager.getPersonaAvailability(persona) }
                )
            }
        }
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.ime)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .testTag("voice_screen_main")
        ) {
            // -------------------------------------------------------------
            // 1. TOP CONTROLS: Circular Menu, Online Badge, Circular Settings
            // -------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Top-Left: Circular Menu Button (Black icon on white circular surface)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { coroutineScope.launch { drawerState.open() } }
                        .testTag("voice_top_menu_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Menu",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Top-Center: Real-Time Live Connection State Badge
                val indicatorColor = when (liveState) {
                    LiveConnectionState.CONNECTING -> Color(0xFFF59E0B)
                    LiveConnectionState.CONNECTED,
                    LiveConnectionState.LISTENING,
                    LiveConnectionState.SPEAKING -> VoiceAccentGreen
                    LiveConnectionState.DISCONNECTED -> Color(0xFF6B7280)
                    LiveConnectionState.ERROR -> Color(0xFFEF4444)
                }
                val badgeLabel = when (liveState) {
                    LiveConnectionState.CONNECTING -> "Connecting..."
                    LiveConnectionState.CONNECTED -> "Connected"
                    LiveConnectionState.LISTENING -> "Listening"
                    LiveConnectionState.SPEAKING -> "Speaking"
                    LiveConnectionState.DISCONNECTED ->
                        if (assistantMode == AssistantMode.OFFLINE) "Offline Mode" else "Disconnected"
                    LiveConnectionState.ERROR -> "Connection Error"
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    modifier = Modifier.clickable {
                        if (liveState == LiveConnectionState.DISCONNECTED || liveState == LiveConnectionState.ERROR) {
                            startOrReconnectLiveVoice()
                        }
                    }.testTag("live_connection_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                        )
                        Text(
                            text = "Gemini Live • $badgeLabel",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Top-Right: Circular Settings Button (Black icon on white circular surface)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { isGeminiVoiceSheetOpen = true }
                        .testTag("voice_top_settings_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Voice & Model Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // -------------------------------------------------------------
            // 2. CENTER AREA: Animated Voice Orb & Real-Time Conversation Card
            // -------------------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Orb Style Selector: Golden Energy Orb vs Cloud Orb
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (orbStyle == "energy") Color(0xFFFEF3C7) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (orbStyle == "energy") Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { orbStyle = "energy" }
                    ) {
                        Text(
                            text = "⚡ Golden Energy Orb",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (orbStyle == "energy") Color(0xFFB45309) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (orbStyle == "cloud") Color(0xFFE0F2FE) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (orbStyle == "cloud") Color(0xFF38BDF8) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { orbStyle = "cloud" }
                    ) {
                        Text(
                            text = "☁️ Cloud Orb",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (orbStyle == "cloud") Color(0xFF0369A1) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                val orbClickAction: () -> Unit = {
                    when (liveState) {
                        LiveConnectionState.SPEAKING -> viewModel.geminiLiveEngine.interruptSpeaking()
                        LiveConnectionState.DISCONNECTED,
                        LiveConnectionState.ERROR -> startOrReconnectLiveVoice()
                        LiveConnectionState.CONNECTING -> Unit
                        LiveConnectionState.CONNECTED,
                        LiveConnectionState.LISTENING -> viewModel.geminiLiveEngine.setMicMuted(!isMicMuted)
                    }
                }

                if (orbStyle == "energy") {
                    // Golden Energy Orb from Canvas implementation
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF060402)),
                        contentAlignment = Alignment.Center
                    ) {
                        EnergyOrb(
                            audioLevel = rmsLevel,
                            state = orbState,
                            size = 260.dp,
                            onClick = orbClickAction
                        )
                    }
                } else {
                    // ChatGPT Cloud Orb
                    VoiceOrb(
                        state = orbState,
                        rmsLevel = rmsLevel,
                        size = 230.dp,
                        onClick = orbClickAction
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Honest Connection / Voice Status Text
                val statusText = when (liveState) {
                    LiveConnectionState.CONNECTING -> "Connecting to Gemini Live..."
                    LiveConnectionState.CONNECTED ->
                        if (isMicMuted) "Connected • Mic muted (tap mic to speak)" else "Connected • Initializing microphone..."
                    LiveConnectionState.LISTENING -> "Listening... (Hindi, Hinglish & English)"
                    LiveConnectionState.SPEAKING -> "Speaking... (Speak or tap orb to interrupt)"
                    LiveConnectionState.DISCONNECTED ->
                        liveError?.message ?: "Disconnected. Tap Reconnect to start Gemini Live."
                    LiveConnectionState.ERROR ->
                        liveError?.message ?: "Connection failed. Tap Reconnect to retry."
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (liveState == LiveConnectionState.ERROR) Color(0xFFEF4444)
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                // Reconnect / Mode Switch / Permission action pills when not connected
                if (liveState == LiveConnectionState.DISCONNECTED || liveState == LiveConnectionState.ERROR) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (liveError?.category == LiveErrorCategory.OFFLINE_MODE_ACTIVE) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        viewModel.setAssistantMode(AssistantMode.AUTO)
                                        startOrReconnectLiveVoice()
                                    }
                                    .testTag("switch_to_auto_live_button")
                            ) {
                                Text(
                                    text = "Switch to Auto & Connect",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { onNavigateToChat() }
                            ) {
                                Text(
                                    text = "Offline Chat",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { startOrReconnectLiveVoice() }
                                    .testTag("reconnect_live_button")
                            ) {
                                Text(
                                    text = if (liveError?.category == LiveErrorCategory.MISSING_PERMISSION) {
                                        "Allow Microphone"
                                    } else {
                                        "Reconnect Gemini Live"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                // Real-time Transcript / Conversation Display
                val latestAssistantMessage = streamingMessage
                    ?: currentMessages.lastOrNull { !it.isUser }

                val displayedText = when {
                    liveAiTranscript.isNotBlank() && isSpeaking -> liveAiTranscript
                    liveUserTranscript.isNotBlank() && isListening -> "\"$liveUserTranscript\""
                    liveAiTranscript.isNotBlank() -> liveAiTranscript
                    latestAssistantMessage != null && isRunningLive -> latestAssistantMessage.text
                    else -> null
                }

                AnimatedVisibility(
                    visible = !displayedText.isNullOrBlank(),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { 20 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { 20 })
                ) {
                    if (displayedText != null) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, start = 8.dp, end = 8.dp)
                                .heightIn(max = 130.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = displayedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // 3. BOTTOM CONTROL BAR: Matches ChatGPT Live Voice Mode
            //    - [Plus +] and [Ask ChatGPT] pill text input
            //    - Circular Mic Mute/Unmute button
            //    - Black circular [X] close button
            // -------------------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                // Attached Image Thumbnail Preview (if any)
                if (selectedImageUri != null) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp, bottom = 8.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Attached image preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { viewModel.setSelectedImage(null) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove photo",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                // Horizontal Row: [Plus + Ask ChatGPT Pill] + [Mic Button] + [Black X Button]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ---------------------------------------------------------
                    // A. Long, white, rounded pill-shaped text input with Plus (+)
                    // ---------------------------------------------------------
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("chatgpt_voice_input_pill")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Plus (+) Icon: opens attachment/options menu
                            IconButton(
                                onClick = { isAttachmentSheetOpen = true },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("voice_attachment_plus_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Attach Options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Text Input Field with placeholder "Ask ChatGPT"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = "Ask ChatGPT",
                                        style = TextStyle(
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                val sendLiveTyped: () -> Unit = {
                                    if (inputText.isNotBlank() || selectedImageUri != null) {
                                        val toSend = inputText
                                        inputText = ""
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        viewModel.sendLiveVoiceTextMessage(toSend)
                                    }
                                }

                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("voice_chatgpt_text_field"),
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = { sendLiveTyped() }
                                    )
                                )
                            }

                            // Send Button inside pill when user enters text
                            AnimatedVisibility(
                                visible = inputText.isNotBlank(),
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                IconButton(
                                    onClick = {
                                        if (inputText.isNotBlank() || selectedImageUri != null) {
                                            val toSend = inputText
                                            inputText = ""
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            viewModel.sendLiveVoiceTextMessage(toSend)
                                        }
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .testTag("voice_send_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send message",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // ---------------------------------------------------------
                    // B. Microphone Mute / Unmute circular button
                    // ---------------------------------------------------------
                    val micButtonBg = if (isMicMuted) {
                        Color(0xFFFFEBEB) // Soft reddish tint when muted
                    } else if (isListening) {
                        VoiceAccentGreen.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }

                    val micIconTint = if (isMicMuted) {
                        Color(0xFFDC2626) // Red when muted
                    } else if (isListening) {
                        VoiceAccentGreen
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }

                    Surface(
                        shape = CircleShape,
                        color = micButtonBg,
                        border = BorderStroke(
                            1.dp,
                            if (isMicMuted) Color(0xFFFCA5A5) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (isMicMuted) {
                                    viewModel.geminiLiveEngine.setMicMuted(false)
                                    if (liveState == LiveConnectionState.DISCONNECTED || liveState == LiveConnectionState.ERROR) {
                                        startOrReconnectLiveVoice()
                                    }
                                } else {
                                    viewModel.geminiLiveEngine.setMicMuted(true)
                                }
                            }
                            .testTag("voice_mic_mute_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (isMicMuted) "Unmute Microphone" else "Mute Microphone",
                                tint = micIconTint,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // ---------------------------------------------------------
                    // C. Black circular X Close button on the far right
                    // ---------------------------------------------------------
                    Surface(
                        shape = CircleShape,
                        color = ChatGptBlack,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .clickable {
                                viewModel.stopLiveVoiceSession()
                                viewModel.speechManager.stopListening()
                                viewModel.speechManager.stopSpeaking()
                                onNavigateToChat()
                            }
                            .testTag("voice_chatgpt_close_x_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Live Voice Mode",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // Attachment & Options Bottom Sheet (Triggered by Plus icon)
            // -------------------------------------------------------------
            if (isAttachmentSheetOpen) {
                ModalBottomSheet(
                    onDismissRequest = { isAttachmentSheetOpen = false },
                    sheetState = rememberModalBottomSheetState()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Add to Live Voice",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Option 1: Attach Photo / Image
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    isAttachmentSheetOpen = false
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = "Attach Photo",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Attach Photo / Image",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Analyze image with multimodal AI",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Option 2: Phone Contacts Tool
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    isAttachmentSheetOpen = false
                                    viewModel.setContactsSheetOpen(true)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Contacts,
                                        contentDescription = "Contacts",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Phone Contacts",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Call or message a contact by voice",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Option 3: Installed Apps Tool
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    isAttachmentSheetOpen = false
                                    viewModel.setAppsSheetOpen(true)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Apps,
                                        contentDescription = "Installed Apps",
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Installed Apps",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Launch and control installed apps by voice",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // Google Gemini Voices Selection Sheet
            // -------------------------------------------------------------
            if (isGeminiVoiceSheetOpen) {
                ModalBottomSheet(
                    onDismissRequest = { isGeminiVoiceSheetOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .padding(bottom = 28.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Voice & Speech Settings",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Gemini Live & TTS voices • ${voiceCapabilities.offlineSttStatus}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { isGeminiVoiceSheetOpen = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = voiceCapabilities.offlineTtsStatus,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(VoicePersona.entries.toTypedArray(), key = { it.id }) { persona ->
                                val isSelected = currentVoicePersona == persona
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.speechManager.setVoicePersona(persona)
                                            if (isRunningLive) {
                                                viewModel.reconnectLiveVoiceSession()
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = persona.displayName,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (persona.id.contains("hindi")) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = Color(0xFFFEF3C7)
                                                    ) {
                                                        Text(
                                                            text = "Hindi & Hinglish",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFFB45309),
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            val availability = viewModel.speechManager.getPersonaAvailability(persona)
                                            Text(
                                                text = "${availability.badgeText} • ${persona.subtitle}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        IconButton(
                                            onClick = { viewModel.speechManager.previewVoice(persona) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.VolumeUp,
                                                contentDescription = "Preview voice",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Offline models button option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    isGeminiVoiceSheetOpen = false
                                    viewModel.setModelManagerOpen(true)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "🤖 Manage Offline Meta Llama Brains",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Manage →",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // Functional Sheets & Overlays
            // -------------------------------------------------------------
            LocalModelManagerSheet(
                isOpen = isModelManagerOpen,
                modelManager = viewModel.llamaModelManager,
                modelStates = modelStates,
                activeLlamaId = activeLlamaId,
                voiceCapabilities = voiceCapabilities,
                modelTestStatus = modelTestStatus,
                onTestLoadModel = { modelId -> viewModel.testLoadLocalModel(modelId) },
                onInstallTtsData = { viewModel.speechManager.openTtsInstallSettings() },
                onDismiss = { viewModel.setModelManagerOpen(false) }
            )

            ContactsSheet(
                isOpen = isContactsSheetOpen,
                contacts = contacts,
                onCallContact = { phone ->
                    viewModel.deviceManager.dialPhoneNumber(phone)
                },
                onDismiss = { viewModel.setContactsSheetOpen(false) },
                onPermissionGranted = { viewModel.refreshContacts() }
            )

            AppsSheet(
                isOpen = isAppsSheetOpen,
                apps = apps,
                onLaunchApp = { pkg ->
                    val success = viewModel.deviceManager.launchAppByPackage(pkg)
                    if (success) {
                        viewModel.setAppsSheetOpen(false)
                    } else {
                        Toast.makeText(context, "Could not open app", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { viewModel.setAppsSheetOpen(false) }
            )

            BiometricLockScreen(
                isLocked = isLocked,
                onUnlockRequest = {
                    (context as? FragmentActivity)?.let { act ->
                        viewModel.biometricLockManager.promptBiometric(
                            activity = act,
                            onSuccess = { viewModel.unlockApp() },
                            onError = {}
                        )
                    }
                }
            )
        }
    }
}
