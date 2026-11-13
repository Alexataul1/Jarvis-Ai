package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessage

/**
 * Pure Minimalist ChatGPT-Style Message Item.
 * - No heavy boxes or robot avatars.
 * - User messages in soft grey rounded pills on the right with quick edit button.
 * - AI messages flow cleanly as plain text directly on the screen in comfortable grey typography.
 * - Small pulsing dot while thinking.
 */
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    isCurrentlySpeaking: Boolean,
    onSpeak: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onEdit: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (message.isUser) {
        // User message on right: Clean soft rounded pill with edit button
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Subtle edit icon before the bubble
            IconButton(
                onClick = { onEdit(message.text) },
                modifier = Modifier
                    .size(28.dp)
                    .testTag("edit_user_message_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit message",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.testTag("user_message_bubble")
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    } else {
        // AI / Jarvis message: Clean plain text directly on background, NO robot avatar, NO boxed card
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .testTag("bot_message_bubble")
        ) {
            if (message.isStreaming && message.text.isBlank()) {
                // Single minimal pulsing dot while thinking
                TypingDotIndicator()
            } else {
                val badgeText = message.engineLabel.ifBlank {
                    when {
                        message.isError -> "Error"
                        message.isOfflineEngine -> "Offline"
                        else -> "Gemini • Online"
                    }
                }
                if (badgeText.isNotBlank()) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        color = if (message.isError) Color(0xFFDC2626)
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .testTag("bot_engine_label")
                    )
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.5.sp,
                        lineHeight = 24.sp,
                        letterSpacing = 0.15.sp
                    ),
                    color = if (message.isError) Color(0xFFB91C1C) else MaterialTheme.colorScheme.onBackground
                )

                if (message.isStreaming) {
                    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(400),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(width = 6.dp, height = 14.dp)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = alpha))
                    )
                }

                // Minimal footer actions: Copy & Read Aloud (Subtle icons)
                if (!message.isStreaming && message.text.isNotBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Jarvis", message.text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp).testTag("copy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy message",
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = {
                                if (isCurrentlySpeaking) onStopSpeaking() else onSpeak(message.text)
                            },
                            modifier = Modifier.size(28.dp).testTag("speak_button")
                        ) {
                            Icon(
                                imageVector = if (isCurrentlySpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                contentDescription = if (isCurrentlySpeaking) "Stop speech" else "Read aloud",
                                tint = if (isCurrentlySpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small, clean pulsing dot indicator while thinking.
 */
@Composable
fun TypingDotIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_dot")
    val animAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = animAlpha))
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = (animAlpha * 0.7f).coerceIn(0.2f, 1f)))
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = (animAlpha * 0.4f).coerceIn(0.2f, 1f)))
        )
    }
}
