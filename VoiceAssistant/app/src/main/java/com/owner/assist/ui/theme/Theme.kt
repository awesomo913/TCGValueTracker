package com.owner.assist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C9DFF),
    secondary = Color(0xFFB8C5FF),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    secondary = Color(0xFF5C7AEA),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun VoiceAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
