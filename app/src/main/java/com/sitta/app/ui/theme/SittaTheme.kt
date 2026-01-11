package com.sitta.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1FD3B4),
    onPrimary = Color.Black,
    secondary = Color(0xFF8B5CF6),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E6E6),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0FB89A),
    onPrimary = Color.White,
    secondary = Color(0xFF7C3AED),
    background = Color(0xFFF7F7F7),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    error = Color(0xFFD94848),
)

@Composable
fun SittaTheme(isDark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}
