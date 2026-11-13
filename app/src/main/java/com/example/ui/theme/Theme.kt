package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = VoiceDarkText,
    onPrimary = Color.White,
    primaryContainer = VoiceSecondaryBgLight,
    onPrimaryContainer = VoicePrimaryTextLight,
    secondary = VoiceAccentGreen,
    onSecondary = Color.White,
    tertiary = VoiceSoftGrey,
    background = VoiceBgLight,
    onBackground = VoiceDarkText,
    surface = VoiceSurfaceLight,
    onSurface = VoicePrimaryTextLight,
    surfaceVariant = VoiceSecondaryBgLight,
    onSurfaceVariant = VoicePrimaryTextLight,
    outline = VoiceBorderLight
)

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = VoiceDarkText,
    primaryContainer = VoiceSecondaryBgDark,
    onPrimaryContainer = VoicePrimaryTextDark,
    secondary = VoiceAccentGreen,
    onSecondary = Color.White,
    tertiary = VoiceSecondaryTextDark,
    background = VoiceBgDark,
    onBackground = VoicePrimaryTextDark,
    surface = VoiceSurfaceDark,
    onSurface = VoicePrimaryTextDark,
    surfaceVariant = VoiceSecondaryBgDark,
    onSurfaceVariant = VoiceSecondaryTextDark,
    outline = VoiceBorderDark
)

@Composable
fun NovaAiTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun JarvisTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    NovaAiTheme(darkTheme = darkTheme, content = content)
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    NovaAiTheme(darkTheme = darkTheme, content = content)
}
