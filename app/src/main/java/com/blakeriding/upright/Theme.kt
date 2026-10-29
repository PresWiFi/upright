package com.blakeriding.upright

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFFFFB547),
    onPrimary = Color(0xFF2A1800),
    secondary = Color(0xFFB9C3FF),
    tertiary = Color(0xFF8BD5B4),
    background = Color(0xFF15131F),
    surface = Color(0xFF15131F),
    surfaceVariant = Color(0xFF252233),
    surfaceContainer = Color(0xFF201D2C),
    surfaceContainerHigh = Color(0xFF2A2638),
    error = Color(0xFFFF8A80),
)

@Composable
fun UprightTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
