package com.fizaan.timetracker.chart

import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.entryLocalDate
import com.fizaan.timetracker.util.entrySeconds
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** What a trend line is made of: time spent, or how often it was started. */
enum class TrendMetric(val label: String, val unit: String) {
    TIME("Time per day", "h"),
    SESSIONS("Sessions per day", ""),
}

/** One plotted point: the trailing average ending on [date]. */
data class TrendPoint(val date: LocalDate, val value: Double)

/**
 * A rolling average of one activity, the way a stock chart draws one.
 *
 * Two conventions are worth stating, because both are choices:
 *
 * A day with nothing on it is a **zero**, not a gap. This is the same rule the
 * frequency tool divides by, and it is what makes the line mean anything — a
 * fortnight off has to pull the average down, or the chart would only ever show
 * how intense the active days were.
 *
 * The window is **trailing and always full**: the point for a day is the mean of
 * that day and the [window] - 1 days before it. Which means the days before the
 * range begins are needed too — see [leadInFrom] — so the first point is a real
 * average rather than the ramp you get from averaging a window that isn't there
 * yet.
 *
 * Nothing here reads the clock: [nowMillis] is passed in, so a running entry
 * counts up to a time the caller decides.
 */
fun rollingTrend(
    entries: List<TimesheetEntry>,
    activityId: Int,
    metric: TrendMetric,
    from: LocalDate,
    to: LocalDate,
    window: Int,
    nowMillis: Long,
): List<TrendPoint> {
    if (to.isBefore(from)) return emptyList()
    val w = window.coerceAtLeast(1)
    val daily = dailyValues(entries, activityId, metric, leadInFrom(from, w), to, nowMillis)
    return daysBetween(from, to).map { day ->
        val start = day.minusDays((w - 1).toLong())
        val sum = daysBetween(start, day).sumOf { daily[it] ?: 0.0 }
        TrendPoint(day, sum / w)
    }
}

/** The first day whose entries a [window]-day average over a range needs. */
fun leadInFrom(from: LocalDate, window: Int): LocalDate =
    from.minusDays((window.coerceAtLeast(1) - 1).toLong())

/**
 * The raw per-day series the average is taken over: hours, or session counts,
 * with every empty day present as a zero.
 *
 * An entry belongs to the day it **began** on, which is how the rest of the app
 * counts — a session that runs past midnight is one session, on the day it
 * started.
 */
fun dailyValues(
    entries: List<TimesheetEntry>,
    activityId: Int,
    metric: TrendMetric,
    from: LocalDate,
    to: LocalDate,
    nowMillis: Long,
): Map<LocalDate, Double> {
    val mine = entries.filter { it.activity == activityId }
    val out = daysBetween(from, to).associateWith { 0.0 }.toMutableMap()
    mine.forEach { e ->
        val day = entryLocalDate(e.begin) ?: return@forEach
        if (day !in out) return@forEach
        out[day] = out.getValue(day) + when (metric) {
            TrendMetric.SESSIONS -> 1.0
            TrendMetric.TIME -> entrySeconds(e.begin, e.end, e.duration, nowMillis) / 3600.0
        }
    }
    return out
}

/** Every date from [from] to [to] inclusive; empty when the range is backwards. */
fun daysBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
    if (to.isBefore(from)) return emptyList()
    val n = ChronoUnit.DAYS.between(from, to)
    return (0..n).map { from.plusDays(it) }
}

/**
 * A rolling-average line, ready to draw: the points, the axis they hang off,
 * and the words printed around them.
 *
 * [signature] is a cheap identity for "this is different data now". The reveal
 * animation keys on it rather than on the point list, which is rebuilt on every
 * recomposition and would otherwise restart the animation constantly.
 */
data class TrendChart(
    val points: List<TrendPoint>,
    val metric: TrendMetric,
    val window: Int,
    val from: LocalDate,
    val to: LocalDate,
    val yTicks: List<Double>,
    val yLabels: List<String>,
    val xLabels: List<String>,
    val title: String,
    val subtitle: String,
    val signature: Int,
) {
    val yMax: Double get() = yTicks.lastOrNull() ?: 1.0
    val peak: TrendPoint? get() = points.maxByOrNull { it.value }
    val average: Double get() = if (points.isEmpty()) 0.0 else points.sumOf { it.value } / points.size
}

fun trendChart(
    points: List<TrendPoint>,
    metric: TrendMetric,
    window: Int,
    from: LocalDate,
    to: LocalDate,
    title: String,
    activityId: Int,
): TrendChart {
    val ticks = niceTicks(points.maxOfOrNull { it.value } ?: 0.0)
    val decimals = tickDecimals(ticks)
    return TrendChart(
        points = points,
        metric = metric,
        window = window,
        from = from,
        to = to,
        yTicks = ticks,
        yLabels = ticks.map { formatTick(it, metric, decimals) },
        xLabels = points.map { it.date.format(XLabelFormat) },
        title = title,
        subtitle = "$window-day rolling average · ${metric.label.lowercase()}",
        signature = listOf(activityId, window, metric.ordinal, from.hashCode(), to.hashCode(),
            points.size).fold(17) { acc, v -> acc * 31 + v },
    )
}

private val XLabelFormat: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM")

/** Axis and readout numbers: "2h", "0.25h", "3", "1.5". */
fun formatTick(value: Double, metric: TrendMetric, decimals: Int = 1): String {
    val n = if (value == floor(value)) value.toLong().toString()
    else "%.${decimals.coerceIn(1, 3)}f".format(value)
    return n + metric.unit
}
