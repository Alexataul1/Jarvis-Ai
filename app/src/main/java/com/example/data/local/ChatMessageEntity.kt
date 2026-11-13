package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.ChatMessage

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val isVoice: Boolean = false,
    val isOfflineEngine: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val engineLabel: String = "",
    @ColumnInfo(defaultValue = "0")
    val isError: Boolean = false
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        sessionId = sessionId,
        text = text,
        isUser = isUser,
        timestamp = timestamp,
        imageUri = imageUri,
        isVoice = isVoice,
        isOfflineEngine = isOfflineEngine,
        isStreaming = false,
        engineLabel = engineLabel,
        isError = isError
    )

    companion object {
        fun fromDomain(msg: ChatMessage): ChatMessageEntity = ChatMessageEntity(
            id = msg.id,
            sessionId = msg.sessionId,
            text = msg.text,
            isUser = msg.isUser,
            timestamp = msg.timestamp,
            imageUri = msg.imageUri,
            isVoice = msg.isVoice,
            isOfflineEngine = msg.isOfflineEngine,
            engineLabel = msg.engineLabel,
            isError = msg.isError
        )
    }
}

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis()
)
