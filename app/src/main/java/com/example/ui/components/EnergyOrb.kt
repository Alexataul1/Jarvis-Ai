package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Energy Particle model matching the React EnergyOrb specification.
 */
private class EnergyParticle(
    var angle: Float,
    val radius: Float,
    val speed: Float,
    val size: Float,
    val phase: Float,
    val drift: Float,
    val colorOffset: Int
)

/**
 * Golden Energy Orb Component
 *
 * Direct Kotlin/Compose port of the React EnergyOrb canvas implementation:
 * - Central golden energy radial core (#fffde1 -> #fff5a0 -> #ffd34d -> #ff9923 -> #ff640a)
 * - 7 multi-frequency harmonic irregular rotating energy rings
 * - 28 rotating fine energy filaments
 * - 400 flowing sparks, embers, and audio-reactive particles
 * - Real-time audioLevel reactivity with smooth pulse scaling and particle velocity boost
 */
@Composable
fun EnergyOrb(
    audioLevel: Float = 0f,
    state: VoiceOrbState = VoiceOrbState.IDLE,
    modifier: Modifier = Modifier,
    size: Dp = 250.dp,
    onClick: () -> Unit = {}
) {
    var time by remember { mutableFloatStateOf(0f) }

    // Silky smooth 60fps frame loop matching requestAnimationFrame(draw)
    LaunchedEffect(Unit) {
        var lastNano = 0L
        while (isActive) {
            withFrameNanos { frameNano ->
                if (lastNano != 0L) {
                    val dt = (frameNano - lastNano) / 16_666_666f
                    time += dt.coerceIn(0.5f, 2.0f)
                } else {
                    time += 1f
                }
                lastNano = frameNano
            }
        }
    }

    // Initialize 400 particles once (optimized for Android Canvas 60fps)
    val particles = remember {
        val rand = Random(42)
        List(400) { index ->
            EnergyParticle(
                angle = rand.nextFloat() * (2f * Math.PI.toFloat()),
                radius = 105f + rand.nextFloat() * 95f,
                speed = 0.001f + rand.nextFloat() * 0.008f,
                size = 0.5f + rand.nextFloat() * 2.2f,
                phase = rand.nextFloat() * 10f,
                drift = rand.nextFloat() * 15f,
                colorOffset = index % 100
            )
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag("energy_orb_component"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val width = this.size.width
            val height = this.size.height
            val center = Offset(width / 2f, height / 2f)

            // Dynamic scale factor mapping 440px canvas design space to local component size
            val canvasScale = min(width, height) / 440f

            val audio = audioLevel.coerceIn(0f, 1f)
            val speaking = (state == VoiceOrbState.SPEAKING)
            val listening = (state == VoiceOrbState.LISTENING)

            val pulse = 1f + sin(time * 0.025f) * 0.025f +
                    (if (speaking) audio * 0.18f else if (listening) audio * 0.10f else 0f)

            // Scaled pulse transform around the center
            scale(scale = pulse, pivot = center) {
                // -------------------------------------------------------------
                // 1. Central golden energy core
                // -------------------------------------------------------------
                val coreRadius = (105f + audio * 18f) * canvasScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0xFFFFFDE1),
                            0.12f to Color(0xFFFFF5A0),
                            0.30f to Color(0xFFFFD34D),
                            0.58f to Color(0xA6FF9923), // rgba(255,153,35,0.65)
                            1.00f to Color(0x00FF640A)  // rgba(255,100,10,0)
                        ),
                        center = center,
                        radius = coreRadius
                    ),
                    center = center,
                    radius = coreRadius
                )

                // -------------------------------------------------------------
                // 2. Irregular energy rings (7 multi-frequency harmonic rings)
                // -------------------------------------------------------------
                for (ring in 0 until 7) {
                    val rotationRad = time * (if (ring % 2 == 1) -0.003f else 0.004f) * (ring + 1)
                    val rotationDeg = Math.toDegrees(rotationRad.toDouble()).toFloat()

                    rotate(degrees = rotationDeg, pivot = center) {
                        val path = Path()
                        val step = 3 // 120 smooth segments per ring
                        for (i in 0..360 step step) {
                            val a = Math.toRadians(i.toDouble()).toFloat()
                            val noise = (sin(a * 7f + time * 0.018f + ring) * 9f +
                                    sin(a * 17f - time * 0.012f) * 5f +
                                    sin(a * 31f + ring) * 2f) * canvasScale

                            val rx = (125f + ring * 10f) * canvasScale + noise
                            val ry = (105f + ring * 8f) * canvasScale + noise * 0.7f

                            val x = center.x + cos(a) * rx
                            val y = center.y + sin(a) * ry

                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()

                        val ringAlpha = if (ring == 0) 0.50f else 0.42f
                        val ringGreen = (125 + ring * 15).coerceIn(0, 255)
                        val ringColor = Color(255, ringGreen, 35, (ringAlpha * 255).toInt())
                        val strokeWidth = (1.2f + (ring % 3)) * canvasScale

                        drawPath(
                            path = path,
                            color = ringColor,
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }

                // -------------------------------------------------------------
                // 3. Fine rotating energy filaments (28 lines)
                // -------------------------------------------------------------
                for (line in 0 until 28) {
                    val base = line * (2f * Math.PI.toFloat()) / 28f
                    val rotation = time * 0.004f * (if (line % 2 == 1) 1f else -1f)

                    val filamentPath = Path()
                    for (j in 0..35) {
                        val p = j / 35f
                        val radius = (90f + p * 100f) * canvasScale
                        val angle = base + rotation + sin(p * 8f + time * 0.02f) * 0.12f

                        val x = center.x + cos(angle) * radius
                        val y = center.y + sin(angle) * radius * 0.85f

                        if (j == 0) filamentPath.moveTo(x, y) else filamentPath.lineTo(x, y)
                    }

                    drawPath(
                        path = filamentPath,
                        color = Color(0x3DFF9D39), // rgba(255, 157, 57, 0.24)
                        style = Stroke(width = 0.85f * canvasScale)
                    )
                }

                // -------------------------------------------------------------
                // 4. Flowing sparks, embers and audio-reactive particles
                // -------------------------------------------------------------
                particles.forEach { p ->
                    p.angle += p.speed * (1f + audio * 4f)
                    val wobble = (sin(time * 0.025f + p.phase) * p.drift) * canvasScale

                    val radius = p.radius * canvasScale + wobble
                    val x = center.x + cos(p.angle) * radius
                    val y = center.y + sin(p.angle) * radius * 0.85f

                    val flicker = (0.4f + abs(sin(time * 0.04f + p.phase)) * 0.6f).coerceIn(0.15f, 1f)
                    val greenVal = (145 + p.colorOffset).coerceIn(100, 255)
                    val particleColor = Color(255, greenVal, 65, (flicker * 255).toInt())

                    drawCircle(
                        color = particleColor,
                        radius = p.size * canvasScale,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}
