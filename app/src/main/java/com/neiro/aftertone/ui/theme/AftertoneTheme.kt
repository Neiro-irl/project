package com.neiro.aftertone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AftertoneBackground = Color(0xFF050505)
val AftertoneSurface = Color(0xFF111111)
val AftertoneSurfaceHigh = Color(0xFF1D1D1F)
val AftertoneDock = Color(0xFF1A1A1C)
val AftertoneAccent = Color(0xFFD6F278)
val AftertoneText = Color(0xFFF7F7F2)
val AftertoneMuted = Color(0xFFA3A3A3)
val AftertoneOutline = Color(0xFF303033)

private val AftertoneColorScheme = darkColorScheme(
    primary = AftertoneAccent,
    onPrimary = Color(0xFF17200A),
    secondary = Color(0xFFB8C3A6),
    onSecondary = Color(0xFF182014),
    background = AftertoneBackground,
    onBackground = AftertoneText,
    surface = AftertoneSurface,
    onSurface = AftertoneText,
    surfaceVariant = AftertoneSurfaceHigh,
    onSurfaceVariant = AftertoneMuted,
    outline = AftertoneOutline,
    error = Color(0xFFFFB4AB),
)

@Composable
fun AftertoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AftertoneColorScheme,
        content = content,
    )
}
