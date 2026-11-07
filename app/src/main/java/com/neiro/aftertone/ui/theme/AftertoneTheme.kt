package com.neiro.aftertone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AftertoneBackground = Color(0xFF0C100E)
val AftertoneSurface = Color(0xFF141A16)
val AftertoneSurfaceHigh = Color(0xFF202823)
val AftertoneAccent = Color(0xFFD6F278)
val AftertoneText = Color(0xFFF2F3ED)
val AftertoneMuted = Color(0xFFA0AAA1)
val AftertoneOutline = Color(0xFF303A33)

private val AftertoneColorScheme = darkColorScheme(
    primary = AftertoneAccent,
    onPrimary = Color(0xFF17200A),
    secondary = Color(0xFFA6C7A9),
    onSecondary = Color(0xFF102016),
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
