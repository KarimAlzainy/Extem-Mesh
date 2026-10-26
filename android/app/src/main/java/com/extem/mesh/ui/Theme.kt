package com.extem.mesh.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A small dark palette: the app is meant to be readable outdoors and in the dark. */
private val meshColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00202C),
    secondary = Color(0xFF9CCC65),
    background = Color(0xFF101418),
    surface = Color(0xFF171D22),
    surfaceVariant = Color(0xFF20272D),
    onBackground = Color(0xFFE6EDF1),
    onSurface = Color(0xFFE6EDF1),
    error = Color(0xFFFF8A80)
)

@Composable
fun ExtemTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = meshColors,
        typography = Typography(),
        content = content
    )
}
