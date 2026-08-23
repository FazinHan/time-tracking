package com.fizaan.timetracker.chart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Where everything on a chart goes, worked out without touching a Canvas.
 *
 * The same drawing code has to look right in a 240dp box on a phone and on an
 * A4 page, which are not the same shape and are nothing like the same size. What
 * makes that possible is [ChartSpec.u]: layout comes from the width and height,
 * but every *typographic* size — text, stroke widths, the radius of a point — is
 * a multiple of one unit the caller sets. On screen that unit is the display
 * density, so a label is a fixed number of dp; on a page it is one PostScript
 * point, the same scale the timesheet PDF is already drawn at.
 *
 * Text is measured through [TextWidth] rather than a Paint, which keeps this
 * file free of android.graphics — and therefore testable, since Paint is a stub
 * in unit tests.
 */
data class ChartSpec(val width: Float, val height: Float, val u: Float)

/** Anything that can say how wide a string comes out. */
fun interface TextWidth {
    fun of(text: String): Float
}

data class ChartMetrics(
    val labelText: Float,
    val titleText: Float,
    val subtitleText: Float,
    val gridStroke: Float,
    val lineStroke: Float,
    val dot: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val plotWidth: Float get() = right - left
    val plotHeight: Float get() = bottom - top
}

/**
 * [yLabelWidth] and [xLabelHeight] are measured by the drawing layer and handed
 * in: the axis gutters have to fit the labels that will actually be printed in
 * them, and only a Paint knows how wide those are.
 */
fun metricsFor(
    spec: ChartSpec,
    yLabelWidth: Float,
    xLabelHeight: Float,
    hasTitle: Boolean,
): ChartMetrics {
    val u = spec.u
    val padL = (yLabelWidth + 7f * u).coerceAtMost(spec.width * 0.32f)
    val padT = if (hasTitle) 30f * u else 8f * u
    val padB = (xLabelHeight + 10f * u).coerceAtMost(spec.height * 0.30f)
    return ChartMetrics(
        labelText = 9f * u,
        titleText = 13f * u,
        subtitleText = 8.5f * u,
        gridStroke = 0.5f * u,
        lineStroke = 1.6f * u,
        dot = 2.2f * u,
        left = padL,
        top = padT,
        right = spec.width - 7f * u,
        bottom = spec.height - padB,
    )
}

/** Where the point at [index] of [count] sits across the plot. */
fun xOf(index: Int, count: Int, left: Float, right: Float): Float =
    if (count <= 1) (left + right) / 2f
    else left + (right - left) * index / (count - 1).toFloat()

/** Where [value] sits up the plot, with zero on the floor. */
fun yOf(value: Double, yMax: Double, top: Float, bottom: Float): Float {
    if (yMax <= 0.0) return bottom
    val frac = (value / yMax).coerceIn(0.0, 1.0)
    return bottom - (frac * (bottom - top)).toFloat()
}

/**
 * How much of the series is drawn at [progress].
 *
 * [drawn] whole points are on the plot; [tail] is how far along the segment
 * towards the next one the line has crept, so it grows smoothly rather than
 * jumping a whole day at a time.
 */
data class Reveal(val drawn: Int, val tail: Float)

fun revealOf(count: Int, progress: Float): Reveal {
    if (count <= 0) return Reveal(0, 0f)
    val front = progress.coerceIn(0f, 1f) * count
    val drawn = floor(front).toInt().coerceIn(0, count)
    return Reveal(drawn, if (drawn >= count) 0f else front - drawn)
}

/** Long enough to watch a short series appear, short enough to sit through a year. */
fun revealMillis(count: Int): Int = (250 + 14 * count).coerceIn(450, 1400)

// ---- axes ----

/**
 * A y-axis that ends on a round number, with ticks at round numbers under it.
 * Steps climb 1 → 2 → 2.5 → 5 → 10, which is what makes 0.4 read as 0.4 rather
 * than 0.3999999.
 */
fun niceTicks(maxValue: Double, target: Int = 4): List<Double> {
    if (!maxValue.isFinite() || maxValue <= 0.0) return listOf(0.0, 1.0)
    val step = niceStep(maxValue / target.coerceAtLeast(1))
    val top = ceil(maxValue / step) * step
    val out = mutableListOf<Double>()
    var v = 0.0
    while (v <= top + step / 2) {
        out.add(round(v, step))
        v += step
    }
    return out
}

private fun niceStep(raw: Double): Double {
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val nice = when {
        norm <= 1.0 -> 1.0
        norm <= 2.0 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

/** Kills the floating-point dust a repeated addition leaves behind. */
private fun round(v: Double, step: Double): Double {
    val decimals = (-floor(log10(step))).toInt().coerceIn(0, 6)
    val f = 10.0.pow(decimals)
    val r = kotlin.math.round(v * f) / f
    return if (abs(r) < 1e-9) 0.0 else r
}

/**
 * Which x-axis labels to print.
 *
 * Strides snap to calendar-sized numbers, because "every 9th day" is a stride
 * that says nothing — a week, a fortnight, a month do. Labelling is anchored at
 * the newest point, since that is the one a reader looks for, and the oldest is
 * added only when it won't collide with its neighbour.
 */
fun pickLabelIndices(
    labels: List<String>,
    slotWidth: Float,
    gap: Float,
    measure: TextWidth,
): List<Int> {
    if (labels.isEmpty()) return emptyList()
    if (labels.size == 1) return listOf(0)
    val widest = labels.maxOf { measure.of(it) }
    val needed = if (slotWidth <= 0f) labels.size
    else ceil(((widest + gap) / slotWidth).toDouble()).toInt().coerceAtLeast(1)
    val stride = NiceStrides.firstOrNull { it >= needed } ?: needed
    val picked = (labels.lastIndex downTo 0 step stride).toMutableList()
    picked.reverse()
    if (picked.firstOrNull() != 0 && picked.first() * slotWidth >= widest + gap) picked.add(0, 0)
    return picked
}

private val NiceStrides = intArrayOf(1, 2, 3, 7, 14, 28, 30, 60, 91, 182, 365)

// ---- month grid ----

/** Square cells, centred in what's left after the labels. */
data class GridGeometry(val cell: Float, val originX: Float, val originY: Float)

/**
 * Always laid out for six rows, whether or not the month needs six: a February
 * that redrew itself at a different size from the May before it would make
 * paging between months feel like the chart was jumping about.
 */
fun gridGeometry(
    spec: ChartSpec,
    m: ChartMetrics,
    rows: Int = MaxWeekRows,
    headerHeight: Float = 0f,
): GridGeometry {
    val availW = spec.width - m.left - (spec.width - m.right)
    val availH = m.bottom - m.top - headerHeight
    val cell = minOf(availW / 7f, availH / rows.coerceAtLeast(1))
    val originX = m.left + (availW - cell * 7f) / 2f
    val originY = m.top + headerHeight + (availH - cell * rows) / 2f
    return GridGeometry(cell, originX, originY)
}

const val MaxWeekRows = 6
