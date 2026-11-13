package com.example.data.model

data class ChatMessage(
    val id: Long = 0,
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val isVoice: Boolean = false,
    val isOfflineEngine: Boolean = false,
    val isStreaming: Boolean = false,
    val engineLabel: String = "",
    val isError: Boolean = false
)

enum class AssistantMode(val title: String) {
    AUTO("Auto (Smart)"),
    ONLINE("Online (Gemini 3.8 Flash)"),
    OFFLINE("Offline (Device Engine)")
}

data class ContactItem(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null
)

data class InstalledAppItem(
    val name: String,
    val packageName: String
)
