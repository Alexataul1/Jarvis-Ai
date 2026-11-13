package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.OrbCyan
import com.example.ui.theme.OrbDeepViolet
import com.example.ui.theme.OrbErrorRed
import com.example.ui.theme.OrbGlowCyan
import com.example.ui.theme.OrbGlowViolet
import com.example.ui.theme.OrbPureWhite
import com.example.ui.theme.OrbRoyalBlue
import com.example.ui.theme.OrbSkyBlue
import com.example.ui.theme.OrbSoftPurple
import kotlin.math.cos
import kotlin.math.sin

enum class VoiceOrbState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

/**
 * ChatGPT-Inspired Animated Voice Orb Component
 * Features smooth flowing cloud-like gradients (Blue, Cyan, Violet, White),
 * organic glowing effects, and real-time audio reactivity.
 */
@Composable
fun VoiceOrb(
    state: VoiceOrbState,
    rmsLevel: Float = 0f,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb_infinite")

    // Slow organic continuous rotation for the cloud gradient
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    VoiceOrbState.PROCESSING -> 3500
                    VoiceOrbState.SPEAKING -> 6000
                    VoiceOrbState.LISTENING -> 8000
                    VoiceOrbState.ERROR -> 12000
                    VoiceOrbState.IDLE -> 14000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    // Gentle breathing scale
    val breathingPulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    VoiceOrbState.SPEAKING -> 1100
                    VoiceOrbState.LISTENING -> 1400
                    else -> 2200
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_pulse"
    )

    // Secondary flowing wave phase for inner organic cloud deformation
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    // Calculate effective audio-reactive scale
    val targetScale = when (state) {
        VoiceOrbState.LISTENING -> {
            val audioBoost = (rmsLevel * 0.45f).coerceIn(0f, 0.45f)
            breathingPulse + audioBoost
        }
        VoiceOrbState.SPEAKING -> {
            // Dynamic pulsating speaking state
            val speakingPulse = (sin(wavePhase * 2f) * 0.08f)
            breathingPulse + 0.06f + speakingPulse
        }
        VoiceOrbState.PROCESSING -> breathingPulse * 0.98f
        VoiceOrbState.ERROR -> 0.96f
        VoiceOrbState.IDLE -> breathingPulse
    }

    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 200),
        label = "smooth_orb_scale"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag("voice_orb_component"),
        contentAlignment = Alignment.Center
    ) {
        // 1. Outer Soft Glow Canvas (Expands beyond the orb)
        Canvas(
            modifier = Modifier.size(size * 1.35f * animatedScale)
        ) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val glowRadius = this.size.minDimension / 2f

            val glowColors = when (state) {
                VoiceOrbState.ERROR -> listOf(
                    OrbErrorRed.copy(alpha = 0.35f),
                    OrbErrorRed.copy(alpha = 0.12f),
                    Color.Transparent
                )
                VoiceOrbState.LISTENING -> listOf(
                    OrbGlowCyan.copy(alpha = 0.45f),
                    OrbGlowViolet.copy(alpha = 0.25f),
                    Color.Transparent
                )
                VoiceOrbState.SPEAKING -> listOf(
                    OrbGlowViolet.copy(alpha = 0.50f),
                    OrbGlowCyan.copy(alpha = 0.30f),
                    Color.Transparent
                )
                VoiceOrbState.PROCESSING -> listOf(
                    OrbSkyBlue.copy(alpha = 0.35f),
                    OrbSoftPurple.copy(alpha = 0.20f),
                    Color.Transparent
                )
                VoiceOrbState.IDLE -> listOf(
                    OrbGlowCyan.copy(alpha = 0.25f),
                    OrbGlowViolet.copy(alpha = 0.15f),
                    Color.Transparent
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = glowColors,
                    center = center,
                    radius = glowRadius
                ),
                center = center,
                radius = glowRadius
            )
        }

        // 2. Main Fluid Cloud Orb Core (Circular with clipping)
        Box(
            modifier = Modifier
                .size(size * animatedScale)
                .clip(CircleShape)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val radius = this.size.minDimension / 2f

                // Base Deep Fluid Gradient
                val baseGradient = Brush.radialGradient(
                    colors = when (state) {
                        VoiceOrbState.ERROR -> listOf(
                            Color(0xFFFF8A80),
                            OrbErrorRed,
                            Color(0xFFB71C1C)
                        )
                        else -> listOf(
                            Color(0xFFE0F2FE),
                            OrbSkyBlue,
                            OrbRoyalBlue,
                            Color(0xFF1E1B4B)
                        )
                    },
                    center = center,
                    radius = radius
                )
                drawCircle(brush = baseGradient, center = center, radius = radius)

                // Rotated Organic Cloud Layer
                rotate(degrees = rotationAngle, pivot = center) {
                    drawOrganicCloudLayer(
                        center = center,
                        radius = radius,
                        wavePhase = wavePhase,
                        state = state
                    )
                }

                // Inner White Soft Core Glow (Breathing light)
                val coreGlowRadius = radius * (0.42f + (if (state == VoiceOrbState.SPEAKING) 0.08f else 0f))
                val coreGlowOffset = Offset(
                    center.x + (cos(wavePhase) * radius * 0.12f),
                    center.y + (sin(wavePhase) * radius * 0.12f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            OrbPureWhite.copy(alpha = 0.85f),
                            OrbPureWhite.copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = coreGlowOffset,
                        radius = coreGlowRadius
                    ),
                    center = coreGlowOffset,
                    radius = coreGlowRadius
                )

                // Soft top-left glass highlight for depth & polish
                val highlightCenter = Offset(center.x - (radius * 0.35f), center.y - (radius * 0.35f))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = highlightCenter,
                        radius = radius * 0.7f
                    ),
                    center = highlightCenter,
                    radius = radius * 0.7f
                )

                // Subtle inner border ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.18f)
                        ),
                        center = center,
                        radius = radius
                    ),
                    center = center,
                    radius = radius
                )
            }
        }
    }
}

/**
 * Draws multiple flowing organic cloud shapes inside the orb.
 */
private fun DrawScope.drawOrganicCloudLayer(
    center: Offset,
    radius: Float,
    wavePhase: Float,
    state: VoiceOrbState
) {
    val cloud1Offset = Offset(
        center.x + (cos(wavePhase) * radius * 0.32f),
        center.y + (sin(wavePhase) * radius * 0.28f)
    )
    val cloud2Offset = Offset(
        center.x + (cos(wavePhase + 2.1f) * radius * 0.36f),
        center.y + (sin(wavePhase + 2.1f) * radius * 0.32f)
    )
    val cloud3Offset = Offset(
        center.x + (sin(wavePhase * 1.4f) * radius * 0.26f),
        center.y + (cos(wavePhase * 1.4f) * radius * 0.30f)
    )

    if (state == VoiceOrbState.ERROR) {
        // Red / Amber alert cloud
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFFCDD2).copy(alpha = 0.8f), Color.Transparent),
                center = cloud1Offset,
                radius = radius * 0.65f
            ),
            center = cloud1Offset,
            radius = radius * 0.65f,
            blendMode = BlendMode.Screen
        )
        return
    }

    // Cyan / Aqua Cloud Blob
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                OrbCyan.copy(alpha = 0.75f),
                OrbSkyBlue.copy(alpha = 0.45f),
                Color.Transparent
            ),
            center = cloud1Offset,
            radius = radius * 0.72f
        ),
        center = cloud1Offset,
        radius = radius * 0.72f,
        blendMode = BlendMode.Screen
    )

    // Deep Violet / Magenta Cloud Blob
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                OrbDeepViolet.copy(alpha = 0.80f),
                OrbSoftPurple.copy(alpha = 0.40f),
                Color.Transparent
            ),
            center = cloud2Offset,
            radius = radius * 0.68f
        ),
        center = cloud2Offset,
        radius = radius * 0.68f,
        blendMode = BlendMode.Screen
    )

    // Luminous White Cloud Heart
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                OrbPureWhite.copy(alpha = 0.90f),
                Color(0xFFBAE6FD).copy(alpha = 0.50f),
                Color.Transparent
            ),
            center = cloud3Offset,
            radius = radius * 0.52f
        ),
        center = cloud3Offset,
        radius = radius * 0.52f,
        blendMode = BlendMode.Screen
    )
}
