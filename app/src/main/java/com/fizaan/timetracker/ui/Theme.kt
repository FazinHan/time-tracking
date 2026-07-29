package com.fizaan.kimaitimer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Kimai / Tabler palette
val KimaiPrimary = Color(0xFF206BC4)
val KimaiRed = Color(0xFFD63939)
val KimaiGreen = Color(0xFF2FB344)
val KimaiBg = Color(0xFF141414)      // really dark grey
val KimaiSurface = Color(0xFF232323) // dark grey surface (dialogs)

private val DarkColors = darkColorScheme(
    primary = KimaiGreen,
    onPrimary = Color.White,
    background = KimaiBg,
    onBackground = Color(0xFFE7ECF3),
    surface = KimaiSurface,
    onSurface = Color(0xFFE7ECF3),
    error = KimaiRed,
)

private val LightColors = lightColorScheme(
    primary = KimaiPrimary,
    onPrimary = Color.White,
    error = KimaiRed,
)

@Composable
fun KimaiTimerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else DarkColors // dark, Kimai-style, either way
    MaterialTheme(colorScheme = colors, content = content)
}
