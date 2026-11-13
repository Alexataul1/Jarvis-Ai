package com.example.ui.components

import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.ChatGptBlack

@Composable
fun ChatInputBar(
    isListening: Boolean,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onOpenLiveVoiceMode: () -> Unit,
    onSendMessage: (String) -> Unit,
    isGenerating: Boolean,
    onStopGenerating: () -> Unit = {},
    modifier: Modifier = Modifier,
    externalText: String? = null,
    onExternalTextConsumed: () -> Unit = {},
    selectedImageUri: Uri? = null,
    onAttachFile: () -> Unit = {},
    onRemoveAttachedImage: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun submitMessage() {
        if (text.isNotBlank() || selectedImageUri != null) {
            val outgoing = text
            text = ""
            focusManager.clearFocus()
            keyboardController?.hide()
            onSendMessage(outgoing)
        }
    }

    LaunchedEffect(externalText) {
        if (!externalText.isNullOrBlank()) {
            text = externalText
            onExternalTextConsumed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Thumbnail preview if an image/file is attached
        if (selectedImageUri != null) {
            Box(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = "Attached file preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable { onRemoveAttachedImage() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove attached file",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // The single clean 'Create image' pill requested by user: photo icon + "Create image"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        text = "Create an image of "
                    }
                    .testTag("create_image_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Create image icon",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Create image",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ChatGPT rounded pure capsule bar with soft border
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File attach '+' icon button
                IconButton(
                    onClick = onAttachFile,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("attach_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Attach file or photo",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Text Input Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = if (isListening) "Listening... say 'Jarvis'..." else "Message Jarvis...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            fontSize = 15.sp
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(ChatGptBlack),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { submitMessage() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_input_text_field")
                    )
                }

                // Voice Mic Button
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val micScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isListening) 1.25f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "mic_scale"
                )

                IconButton(
                    onClick = {
                        if (isListening) onStopVoice() else onStartVoice()
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .scale(micScale)
                        .testTag("voice_mic_button")
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop voice" else "Voice input",
                        tint = if (isListening) Color.Red else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // If generating: show Stop button; if text/file entered: show Send arrow; otherwise show Voice Waveform
                if (isGenerating) {
                    IconButton(
                        onClick = onStopGenerating,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ChatGptBlack)
                            .testTag("stop_generating_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop generating",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else if (text.isNotBlank() || selectedImageUri != null) {
                    IconButton(
                        onClick = { submitMessage() },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ChatGptBlack)
                            .testTag("send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    // ChatGPT's signature Voice Chat icon (Waveform vertical sound lines button)
                    IconButton(
                        onClick = onOpenLiveVoiceMode,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ChatGptBlack)
                            .testTag("live_voice_mode_button")
                    ) {
                        ChatGptVoiceWaveformIcon(tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Signature ChatGPT Voice waveform icon: 4 vertical rounded sound bars of varying heights
 */
@Composable
fun ChatGptVoiceWaveformIcon(
    tint: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint)
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint)
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint)
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint)
        )
    }
}
