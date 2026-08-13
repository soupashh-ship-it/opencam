package com.opencam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4CC9F0)
private val HotPink = Color(0xFFF72585)
private val Background = Color(0xFF0B0F1A)
private val Surface = Color(0xFF141B2B)
private val SurfaceHigh = Color(0xFF1E2A45)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00242E),
    secondary = HotPink,
    onSecondary = Color.White,
    background = Background,
    onBackground = Color(0xFFE6EAF5),
    surface = Surface,
    onSurface = Color(0xFFE6EAF5),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFB8C2D9),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF3A4A6B),
)

@Composable
fun OpenCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
