package com.stef.vocalremover.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CoralMidnightColors = darkColorScheme(
    primary = Color(0xFFED615B),
    onPrimary = Color(0xFF19263B),
    primaryContainer = Color(0xFF253A5B),
    onPrimaryContainer = Color(0xFFFFC8B2),
    background = Color(0xFF19263B),
    onBackground = Color(0xFFFFE9D6),
    surface = Color(0xFF253A5B),
    onSurface = Color(0xFFFFE9D6),
    onSurfaceVariant = Color(0xFFFFC8B2)
)

@Composable
fun VocalRemoverTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CoralMidnightColors, content = content)
}
