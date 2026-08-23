package com.fizaan.timetracker.chart

import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.entryLocalDate
import com.fizaan.timetracker.util.entrySeconds
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

/** One box in the calendar: a real day, however busy it was. */
data class DayCell(
    val date: LocalDate,
    val sessions: Int,
    val seconds: Long,
    /** 0f for a day with nothing on it, 1f for the busiest day of the month. */
    val intensity: Float,
)

/**
 * A month of one activity, as a grid of days.
 *
 * The awkward parts are the ones worth having outside a Canvas: which column the
 * 1st lands in, how many weeks that pushes the month across, and how a count
 * turns into a shade. [firstColumn] and [rows] are both derived from
 * [weekStart], so a calendar starting on Sunday needs no separate code path.
 */
data class MonthChart(
    val month: YearMonth,
    val weekStart: DayOfWeek,
    /** Column of the 1st, 0..6. */
    val firstColumn: Int,
    /** Weeks the month spans, 4..6. */
    val rows: Int,
    /** One per day of the month, index 0 being the 1st. */
    val cells: List<DayCell>,
    val busiest: Int,
    val totalSessions: Int,
    val totalSeconds: Long,
    val activeDays: Int,
    val weekdayLabels: List<String>,
    /** Index of today among [cells], or null when this isn't the current month. */
    val todayIndex: Int?,
    val title: String,
    val subtitle: String,
    val signature: Int,
)

fun monthChart(
    entries: List<TimesheetEntry>,
    activityId: Int,
    month: YearMonth,
    title: String,
    today: LocalDate,
    nowMillis: Long,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
    locale: Locale = Locale.getDefault(),
): MonthChart {
    val mine = entries.filter { it.activity == activityId }
    val sessions = mutableMapOf<LocalDate, Int>()
    val seconds = mutableMapOf<LocalDate, Long>()
    mine.forEach { e ->
        val day = entryLocalDate(e.begin) ?: return@forEach
        if (YearMonth.from(day) != month) return@forEach
        sessions[day] = (sessions[day] ?: 0) + 1
        seconds[day] = (seconds[day] ?: 0L) + entrySeconds(e.begin, e.end, e.duration, nowMillis)
    }
    val busiest = sessions.values.maxOrNull() ?: 0
    val cells = (1..month.lengthOfMonth()).map { day ->
        val date = month.atDay(day)
        val count = sessions[date] ?: 0
        DayCell(
            date = date,
            sessions = count,
            seconds = seconds[date] ?: 0L,
            intensity = intensityOf(count, busiest),
        )
    }
    return MonthChart(
        month = month,
        weekStart = weekStart,
        firstColumn = firstColumnOf(month, weekStart),
        rows = rowsFor(month, weekStart),
        cells = cells,
        busiest = busiest,
        totalSessions = sessions.values.sum(),
        totalSeconds = seconds.values.sum(),
        activeDays = sessions.count { it.value > 0 },
        weekdayLabels = weekdayLabels(weekStart, locale),
        todayIndex = if (YearMonth.from(today) == month) today.dayOfMonth - 1 else null,
        title = title,
        subtitle = subtitleFor(month, sessions.values.sum(), locale),
        signature = 31 * (month.hashCode() * 31 + activityId) + mine.size,
    )
}

/**
 * A day with one session has to be visibly filled, or a quiet month reads as an
 * empty one — so the scale starts at a quarter rather than at nothing, and only
 * the difference above that is proportional.
 */
fun intensityOf(count: Int, busiest: Int): Float = when {
    count <= 0 -> 0f
    busiest <= 1 -> 1f
    else -> 0.25f + 0.75f * (count - 1).toFloat() / (busiest - 1).toFloat()
}

fun firstColumnOf(month: YearMonth, weekStart: DayOfWeek): Int =
    Math.floorMod(month.atDay(1).dayOfWeek.value - weekStart.value, 7)

fun rowsFor(month: YearMonth, weekStart: DayOfWeek): Int =
    ceil((firstColumnOf(month, weekStart) + month.lengthOfMonth()) / 7.0).toInt()

/** Row and column of the day at [dayIndex], counting from the 1st at index 0. */
fun cellPosition(chart: MonthChart, dayIndex: Int): Pair<Int, Int> {
    val slot = chart.firstColumn + dayIndex
    return slot / 7 to slot % 7
}

private fun weekdayLabels(weekStart: DayOfWeek, locale: Locale): List<String> =
    (0..6).map { weekStart.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale) }

private fun subtitleFor(month: YearMonth, sessions: Int, locale: Locale): String {
    val name = month.month.getDisplayName(TextStyle.FULL, locale)
    return "$name ${month.year} · $sessions session${if (sessions == 1) "" else "s"}"
}
