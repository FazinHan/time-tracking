package com.fizaan.timetracker.chart

/**
 * The ink a chart is drawn in, as packed ARGB.
 *
 * Colours cross into this package as plain Ints on purpose: it keeps both
 * Compose's Color and android.graphics.Color out of the chart arithmetic, which
 * is what lets the arithmetic be unit-tested at all.
 *
 * [background] of 0 means paint nothing — the screen already sits on the app's
 * surface, and a transparent PNG wants nothing behind it either.
 */
data class ChartTheme(
    val background: Int,
    val grid: Int,
    val axis: Int,
    val label: Int,
    val title: Int,
    val subtitle: Int,
    val emphasis: Int,
    /** A day with nothing on it, in the calendar grid. */
    val empty: Int,
    /** True when the ink is light on a dark ground, as on screen. */
    val lightInk: Boolean,
) {
    /**
     * The palette was chosen against the app's dark surface, where colours can
     * afford to be bright. The same hue on white is glaring, so it is taken down
     * a little on paper.
     */
    fun series(argb: Int): Int = if (lightInk) argb else darken(argb, 0.84f)

    companion object {
        /** White ground, dark ink: what an export is read on. */
        val Paper = ChartTheme(
            background = 0xFFFFFFFF.toInt(),
            grid = 0x1A000000,
            axis = 0xFF5A5A5A.toInt(),
            label = 0xFF3C3C3C.toInt(),
            title = 0xFF141414.toInt(),
            subtitle = 0xFF666666.toInt(),
            emphasis = 0xFF141414.toInt(),
            empty = 0x14000000,
            lightInk = false,
        )

        /**
         * The on-screen variant, built from the theme's own foreground colour so
         * it follows the accent the user picked in setup.
         */
        fun screen(onBackground: Int): ChartTheme = ChartTheme(
            background = 0,
            grid = withAlpha(onBackground, 0.10f),
            axis = withAlpha(onBackground, 0.45f),
            label = withAlpha(onBackground, 0.70f),
            title = withAlpha(onBackground, 1f),
            subtitle = withAlpha(onBackground, 0.55f),
            emphasis = withAlpha(onBackground, 0.9f),
            empty = withAlpha(onBackground, 0.08f),
            lightInk = true,
        )

        /**
         * A PDF page has no alpha of its own: whatever is not painted is left to
         * whatever the viewer puts behind it, which is usually white but is dark
         * in a night-mode reader — and dark ink on that is a blank page. So a
         * PDF always gets a real background, and "transparent" only means
         * something for a PNG.
         */
        fun forExport(transparent: Boolean, pdf: Boolean): ChartTheme =
            if (transparent && !pdf) Paper.copy(background = 0) else Paper
    }
}

fun withAlpha(argb: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
    return (a shl 24) or (argb and 0x00FFFFFF)
}

/** Scales a colour's channels towards black, keeping its alpha. */
fun darken(argb: Int, factor: Float): Int {
    val f = factor.coerceIn(0f, 1f)
    val a = (argb ushr 24) and 0xFF
    val r = (((argb shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
    val g = (((argb shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
    val b = ((argb and 0xFF) * f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
