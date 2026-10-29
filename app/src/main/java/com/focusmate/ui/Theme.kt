package com.focusmate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFFDF8F1)
private val Ink = Color(0xFF1F1B16)
private val Coral = Color(0xFFD9663F)
private val Sand = Color(0xFFEFE6DA)

private val Light = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Sand,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE58A66),
    background = Color(0xFF17140F),
    onBackground = Color(0xFFF3EBE1),
    surface = Color(0xFF221D17),
    onSurface = Color(0xFFF3EBE1),
)

@Composable
fun FocusMateTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
