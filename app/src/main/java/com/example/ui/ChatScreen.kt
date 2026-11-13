package com.example.ui

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.data.model.AssistantMode
import com.example.ui.components.AppsSheet
import com.example.ui.components.BiometricLockScreen
import com.example.ui.components.ChatDrawerContent
import com.example.ui.components.ChatGptVoiceWaveformIcon
import com.example.ui.components.ChatInputBar
import com.example.ui.components.ChatMessageItem
import com.example.ui.components.ContactsSheet
import com.example.ui.components.LocalModelManagerSheet
import com.example.ui.components.VoiceChatModal
import com.example.ui.theme.ChatGptBlack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    activity: FragmentActivity,
    onNavigateToVoice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateToVoice() }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val requestRecordAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.speechManager.startListening { voiceText ->
                viewModel.sendMessage(voiceText, isVoice = true)
            }
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for voice input",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        viewModel.setSelectedImage(uri)
    }

    val selectedImageUri by viewModel.selectedImageUri.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val messages by viewModel.currentMessages.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val assistantMode by viewModel.assistantMode.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isLocked by viewModel.isLocked.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    val isVoiceModalOpen by viewModel.isVoiceModalOpen.collectAsState()
    val isContactsSheetOpen by viewModel.isContactsSheetOpen.collectAsState()
    val isAppsSheetOpen by viewModel.isAppsSheetOpen.collectAsState()
    val isModelManagerOpen by viewModel.isModelManagerOpen.collectAsState()
    val modelStates by viewModel.llamaModelManager.modelStates.collectAsState()
    val activeLlamaId by viewModel.llamaModelManager.activeLlamaModelId.collectAsState()

    val isListening by viewModel.speechManager.isListening.collectAsState()
    val isSpeaking by viewModel.speechManager.isSpeaking.collectAsState()
    val rmsLevel by viewModel.speechManager.rmsLevel.collectAsState()
    val recognizedText by viewModel.speechManager.recognizedText.collectAsState()
    val isMuted by viewModel.speechManager.isMuted.collectAsState()
    val speechRate by viewModel.speechManager.speechRate.collectAsState()
    val currentVoicePersona by viewModel.speechManager.currentVoicePersona.collectAsState()
    val isNetworkConnected by viewModel.deviceManager.isOnlineFlow.collectAsState()
    val hasError by viewModel.speechManager.hasError.collectAsState()
    val errorMessage by viewModel.speechManager.errorMessage.collectAsState()
    val voiceCapabilities by viewModel.speechManager.capabilities.collectAsState()
    val modelTestStatus by viewModel.modelTestStatus.collectAsState()

    val contacts by viewModel.contacts.collectAsState()
    val apps by viewModel.installedApps.collectAsState()

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // Auto-scroll to bottom on message change, streaming update, or when keyboard opens
    LaunchedEffect(messages.size, streamingMessage?.text, imeBottomPx > 0) {
        val totalCount = messages.size + if (streamingMessage != null) 1 else 0
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    val pendingEditText by viewModel.pendingEditText.collectAsState()

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
                    isNetworkConnected = isNetworkConnected,
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
                    onSetMode = { mode -> viewModel.setAssistantMode(mode) },
                    onSelectVoicePersona = { persona -> viewModel.speechManager.setVoicePersona(persona) },
                    onPreviewVoicePersona = { persona -> viewModel.speechManager.previewVoice(persona) },
                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                    onToggleBiometric = { enabled -> viewModel.toggleBiometricEnabled(enabled) },
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
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Jarvis",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 2.dp)
                            )

                            // Segmented Switcher [ 💬 Chat | 🎙️ Voice ]
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.testTag("chat_mode_switcher")
                            ) {
                                Row(
                                    modifier = Modifier.padding(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Active Chat Tab
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                    ) {
                                        Text(
                                            text = "Chat",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                        )
                                    }

                                    // Switch to Voice Tab
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable { onNavigateToVoice() }
                                            .padding(horizontal = 12.dp, vertical = 5.dp)
                                            .testTag("switch_to_voice_tab")
                                    ) {
                                        Text(
                                            text = "Voice",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("open_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open navigation menu",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    },
                    actions = {
                        // Revisit previous conversations (History)
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("chat_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Revisit previous conversations",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Switch to Voice Mode
                        IconButton(
                            onClick = onNavigateToVoice,
                            modifier = Modifier.testTag("top_voice_chat_button")
                        ) {
                            ChatGptVoiceWaveformIcon(
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Clear chat
                        IconButton(
                            onClick = { viewModel.clearCurrentMessages() },
                            modifier = Modifier.testTag("clear_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear chat",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                ChatInputBar(
                    isListening = isListening,
                    onStartVoice = {
                        if (!viewModel.speechManager.hasRecordAudioPermission()) {
                            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.speechManager.startListening { voiceText ->
                                viewModel.sendMessage(voiceText, isVoice = true)
                            }
                        }
                    },
                    onStopVoice = { viewModel.speechManager.stopListening() },
                    onOpenLiveVoiceMode = onNavigateToVoice,
                    onSendMessage = { text -> viewModel.sendMessage(text) },
                    isGenerating = isGenerating,
                    onStopGenerating = { viewModel.stopGeneration() },
                    externalText = pendingEditText,
                    onExternalTextConsumed = { viewModel.setPendingEditText(null) },
                    selectedImageUri = selectedImageUri,
                    onAttachFile = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemoveAttachedImage = { viewModel.setSelectedImage(null) },
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.ime)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    )
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier = modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                if (messages.isEmpty() && streamingMessage == null) {
                    // Clean spacious canvas - center clutter deleted as requested
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    // Chat Messages List
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ChatMessageItem(
                                message = msg,
                                isCurrentlySpeaking = isSpeaking,
                                onSpeak = { text -> viewModel.speechManager.speak(text) },
                                onStopSpeaking = { viewModel.speechManager.stopSpeaking() },
                                onEdit = { textToEdit -> viewModel.setPendingEditText(textToEdit) }
                            )
                        }

                        if (streamingMessage != null) {
                            item {
                                ChatMessageItem(
                                    message = streamingMessage!!,
                                    isCurrentlySpeaking = false,
                                    onSpeak = {},
                                    onStopSpeaking = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Overlays
    VoiceChatModal(
        isOpen = isVoiceModalOpen,
        isListening = isListening,
        isSpeaking = isSpeaking,
        isProcessing = isGenerating,
        hasError = hasError,
        errorMessage = errorMessage,
        isMuted = isMuted,
        speechRate = speechRate,
        rmsLevel = rmsLevel,
        recognizedText = recognizedText,
        onStartListening = {
            viewModel.speechManager.startListening { transcript ->
                viewModel.sendMessage(transcript, isVoice = true)
            }
        },
        onStopListening = { viewModel.speechManager.stopListening() },
        onStopSpeaking = { viewModel.speechManager.stopSpeaking() },
        onToggleMute = { viewModel.speechManager.toggleMute() },
        onSetSpeechRate = { rate -> viewModel.speechManager.setSpeechRate(rate) },
        onClearError = { viewModel.speechManager.clearError() },
        onClose = { viewModel.setVoiceModalOpen(false) }
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

    BiometricLockScreen(
        isLocked = isLocked,
        onUnlockRequest = {
            viewModel.biometricLockManager.promptBiometric(
                activity = activity,
                onSuccess = { viewModel.unlockApp() },
                onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
            )
        }
    )
}
