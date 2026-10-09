package com.lumacam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFA94D)
val Teal = Color(0xFF3FC1C9)
val Scrim = Color(0x99000000)

private val colors = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    secondary = Teal,
    onSecondary = Color.Black,
    background = Color.Black,
    surface = Color(0xFF15181D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF22262D),
    onSurfaceVariant = Color(0xFFCDD3DA),
)

@Composable
fun LumaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
