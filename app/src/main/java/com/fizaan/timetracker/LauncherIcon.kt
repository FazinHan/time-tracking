package com.fizaan.timetracker

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import com.fizaan.timetracker.data.Prefs
import kotlin.math.sqrt

/**
 * The launcher icon, kept roughly in step with the accent.
 *
 * Android has no API for recolouring an icon — it is a static resource — so the
 * app ships one prebuilt icon per hue as an `activity-alias` and enables the
 * closest. Exactly one is ever enabled, so only one entry appears in the tray;
 * the rest are disabled components the launcher cannot see.
 *
 * The three theme presets have a slot of their own, so picking one is an exact
 * match. Any other colour lands on its nearest neighbour and is an approximation
 * by design.
 */
enum class IconVariant(val alias: String, val color: Color) {
    RED("LauncherRed", Color(0xFFD63939)),
    ORANGE("LauncherOrange", Color(0xFFD67B39)),
    AMBER("LauncherAmber", Color(0xFFC9A227)),
    LIME("LauncherLime", Color(0xFF7FB339)),
    GREEN("LauncherGreen", Color(0xFF2FB344)),
    EMERALD("LauncherEmerald", Color(0xFF2FB37E)),
    CYAN("LauncherCyan", Color(0xFF2FA9B3)),
    BLUE("LauncherBlue", Color(0xFF206BC4)),
    INDIGO("LauncherIndigo", Color(0xFF3B4FC4)),
    VIOLET("LauncherViolet", Color(0xFF6B3FC4)),
    MAGENTA("LauncherMagenta", Color(0xFFB33FC4)),
    PINK("LauncherPink", Color(0xFFC43F82)),
}

/** The variant enabled on a fresh install; must match the manifest. */
val DefaultIconVariant = IconVariant.GREEN

object LauncherIcon {

    /**
     * Point the launcher at the icon nearest [accent].
     *
     * A no-op unless the choice actually changed: switching aliases makes the
     * icon blink out and back in the tray, which is not something to do on
     * every start.
     */
    fun apply(context: Context, accent: Int) {
        val prefs = Prefs(context)
        val want = nearest(Color(accent))
        if (want == prefs.iconVariant) return

        val pm = context.packageManager
        // Enable the new alias before retiring the others, so there is never an
        // instant where the app has no launcher entry at all.
        setState(pm, context, want, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        IconVariant.entries.filter { it != want }.forEach {
            setState(pm, context, it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }
        prefs.iconVariant = want
    }

    private fun setState(pm: PackageManager, context: Context, v: IconVariant, state: Int) {
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, "${context.packageName}.${v.alias}"),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Nearest variant by "redmean" distance — a cheap approximation of how far
     * apart two colours look, which beats comparing hue alone: a washed-out or
     * near-grey accent has barely any hue to compare.
     */
    fun nearest(accent: Color): IconVariant =
        IconVariant.entries.minBy { distance(accent, it.color) }

    private fun distance(a: Color, b: Color): Float {
        val dr = (a.red - b.red) * 255f
        val dg = (a.green - b.green) * 255f
        val db = (a.blue - b.blue) * 255f
        val rMean = (a.red + b.red) * 255f / 2f
        return sqrt(
            (2f + rMean / 256f) * dr * dr + 4f * dg * dg + (2f + (255f - rMean) / 256f) * db * db
        )
    }
}
