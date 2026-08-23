package com.fizaan.timetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Offline fallback for the swatch picker — a copy of Kimai's default palette.
 * The live list comes from GET /api/config/colors; the server rejects any
 * color not in its configured choices.
 */
val DefaultColorChoices = mapOf(
    "Silver" to "#c0c0c0", "Gray" to "#808080", "Maroon" to "#800000",
    "Brown" to "#a52a2a", "Red" to "#ff0000", "Orange" to "#ffa500",
    "Gold" to "#ffd700", "Yellow" to "#ffff00", "Peach" to "#ffdab9",
    "Khaki" to "#f0e68c", "Olive" to "#808000", "Lime" to "#00ff00",
    "Jelly" to "#9acd32", "Green" to "#008000", "Teal" to "#008080",
    "Aqua" to "#00ffff", "LightBlue" to "#add8e6", "DeepSky" to "#00bfff",
    "Dodger" to "#1e90ff", "Blue" to "#0000ff", "Navy" to "#000080",
    "Purple" to "#800080", "Fuchsia" to "#ff00ff", "Violet" to "#ee82ee",
    "Rose" to "#ffe4e1", "Lavender" to "#E6E6FA",
)

/**
 * The colour grid, wherever one is offered: the entry editor, and the two
 * chart tools. [choices] is the server's palette when it has been fetched,
 * since a colour outside it is one the server would refuse.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(
    choices: Map<String, String>,
    selected: String?,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    onPick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.ifEmpty { DefaultColorChoices }.values.forEach { hex ->
            val color = parseHexColor(hex) ?: return@forEach
            val isSelected = selected?.equals(hex, ignoreCase = true) == true
            Swatch(
                color = color,
                selected = isSelected,
                size = size,
                onClick = { onPick(hex) },
            )
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.25f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
