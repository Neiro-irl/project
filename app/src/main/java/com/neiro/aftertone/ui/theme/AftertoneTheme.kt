package com.neiro.aftertone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AftertoneBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background

val AftertoneSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surface

val AftertoneSurfaceHigh: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

val AftertoneDock: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer

val AftertoneAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

val AftertoneText: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

val AftertoneMuted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

val AftertoneOutline: Color
    @Composable get() = MaterialTheme.colorScheme.outline

private fun AftertonePalette.toColorScheme() = darkColorScheme(
    primary = Color(accentArgb.toInt()),
    onPrimary = Color(onAccentArgb.toInt()),
    secondary = Color(secondaryArgb.toInt()),
    onSecondary = Color(onSecondaryArgb.toInt()),
    background = Color(backgroundArgb.toInt()),
    onBackground = Color(textArgb.toInt()),
    surface = Color(surfaceArgb.toInt()),
    onSurface = Color(textArgb.toInt()),
    surfaceVariant = Color(surfaceHighArgb.toInt()),
    onSurfaceVariant = Color(mutedArgb.toInt()),
    surfaceContainer = Color(dockArgb.toInt()),
    outline = Color(outlineArgb.toInt()),
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun AftertoneTheme(
    palette: AftertonePalette = AftertonePalette.default,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = palette.toColorScheme(),
        content = content,
    )
}
