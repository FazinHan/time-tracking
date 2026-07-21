package com.fizaan.kimaitimer.ui

import androidx.compose.ui.graphics.Color
import com.fizaan.kimaitimer.data.Activity

/**
 * Fallback categorical palette for activities with no server-side color,
 * validated (CVD + contrast) against the app's dark surface. Slots are
 * assigned in fixed order of activity id so a given activity always gets
 * the same color, regardless of which entries are on screen.
 */
val FallbackPalette = listOf(
    Color(0xFF3987E5), // blue
    Color(0xFFD95926), // orange
    Color(0xFF199E70), // aqua
    Color(0xFFC98500), // yellow
    Color(0xFFD55181), // magenta
    Color(0xFF008300), // green
    Color(0xFF9085E9), // violet
    Color(0xFFE66767), // red
)

val UntaggedGray = Color(0xFF898781)

/** Parse "#RRGGBB" / "#AARRGGBB" from the server; null if malformed. */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Exception) {
        null
    }
}

/**
 * Server color if set, else a fallback slot indexed among the *uncolored*
 * activities only — keeps fallback hues from landing on top of hues the user
 * already assigned on the server (e.g. two near-identical greens).
 */
fun colorForActivity(activityId: Int, activities: List<Activity>): Color {
    val act = activities.firstOrNull { it.id == activityId }
    parseHexColor(act?.color)?.let { return it }
    val uncolored = activities.filter { parseHexColor(it.color) == null }.map { it.id }.sorted()
    val idx = uncolored.indexOf(activityId)
    return FallbackPalette[(if (idx >= 0) idx else activityId) % FallbackPalette.size]
}

/**
 * Tag colors: the user's productive/unproductive classification gets status
 * semantics (good/critical); other tags take palette slots in name order.
 */
fun colorForTag(tag: String, allTags: List<String>): Color = when (tag.lowercase()) {
    "productive" -> Color(0xFF0CA30C)
    "unproductive" -> Color(0xFFD03B3B)
    "" -> UntaggedGray
    else -> {
        val others = allTags.filter { it.lowercase() !in setOf("productive", "unproductive") }.sorted()
        val idx = others.indexOf(tag)
        FallbackPalette[(if (idx >= 0) idx else 0) % FallbackPalette.size]
    }
}
