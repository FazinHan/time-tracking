package com.fizaan.timetracker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The app is dark whatever the system says: a near-black ground with a single
// accent colour on top of it. Only the accent is the user's to choose.
val AppBg = Color(0xFF141414)      // really dark grey
val AppSurface = Color(0xFF232323) // dark grey surface (dialogs, cards)
val AppOn = Color(0xFFE7ECF3)
private val AppSurfaceVariant = Color(0xFF2E2E2E)
private val AppOutline = Color(0xFF6C6C6C)

/**
 * The stop signal. Deliberately not themed: a running timer and its stop button
 * have to read the same whatever accent is set, and red is the one colour that
 * says "this is live" on sight.
 */
val StopRed = Color(0xFFD63939)

/** The offered accents. Anything else is mixed by hand in the RGB picker. */
enum class Accent(val label: String, val color: Color) {
    GREEN("Green", Color(0xFF2FB344)),
    RED("Red", Color(0xFFD63939)),
    BLUE("Blue", Color(0xFF206BC4)),
}

val DefaultAccent: Color = Accent.GREEN.color

/**
 * How far apart two colours are, as a WCAG contrast ratio: 1.0 is invisible,
 * 21.0 is black on white.
 */
fun contrastRatio(a: Color, b: Color): Float {
    val hi = maxOf(a.luminance(), b.luminance())
    val lo = minOf(a.luminance(), b.luminance())
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * The accent is drawn on both grounds, so the worse of the two decides whether
 * it can be read at all. [AppSurface] is the lighter and therefore binding for
 * anything bright; [AppBg] catches accents so dark they disappear into it.
 */
fun accentContrast(accent: Color): Float =
    minOf(contrastRatio(accent, AppBg), contrastRatio(accent, AppSurface))

/**
 * The floor an accent has to clear. Below WCAG's 3:1 for UI components, because
 * this accent is decoration on a near-black ground rather than the thing being
 * read — 2:1 still keeps a colour from disappearing into the background.
 */
const val MinAccentContrast = 2f

fun accentUsable(accent: Color): Boolean = accentContrast(accent) >= MinAccentContrast

/** Text and icons drawn *on* the accent — black once the accent is bright. */
fun onAccent(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)

@Composable
fun TimeTrackerTheme(accent: Color = DefaultAccent, content: @Composable () -> Unit) {
    // The container roles are the accent muted into the surface, so the parts
    // Material colours for us — a selected drawer row, a switch track — follow
    // the choice too instead of falling back to Material's purple.
    val container = mix(AppSurface, accent, 0.30f)
    val colors = darkColorScheme(
        primary = accent,
        onPrimary = onAccent(accent),
        primaryContainer = container,
        onPrimaryContainer = AppOn,
        secondary = accent,
        onSecondary = onAccent(accent),
        secondaryContainer = container,
        onSecondaryContainer = AppOn,
        tertiary = accent,
        onTertiary = onAccent(accent),
        background = AppBg,
        onBackground = AppOn,
        surface = AppSurface,
        onSurface = AppOn,
        surfaceVariant = AppSurfaceVariant,
        onSurfaceVariant = AppOn,
        outline = AppOutline,
        error = StopRed,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
