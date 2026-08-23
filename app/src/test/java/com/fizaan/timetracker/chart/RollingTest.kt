package com.fizaan.timetracker.chart

import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.epochMillis
import com.fizaan.timetracker.util.formatKimai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The arithmetic behind the rolling-average plot.
 *
 * A line chart is convincing whether or not it is right, which is exactly why
 * the numbers under it are worth pinning down: the empty days that have to count
 * as zeros, and the lead-in that keeps the first point from being an artefact of
 * the window rather than a fact about the habit.
 */
class RollingTest {

    private val activity = 4

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        formatKimai(LocalDateTime.of(2026, 8, day, hour, minute))

    private fun entry(day: Int, fromHour: Int, toHour: Int?, id: Int = day * 100 + fromHour) =
        TimesheetEntry(
            id = id,
            begin = at(day, fromHour),
            end = toHour?.let { at(day, it) },
            duration = null,
            description = null,
            tags = null,
            activity = activity,
            project = 1,
        )

    private val now = epochMillis(LocalDateTime.of(2026, 8, 31, 12, 0))

    private fun day(n: Int) = LocalDate.of(2026, 8, n)

    // ---- daily series ----

    @Test
    fun `a day with nothing on it is a zero, not a gap`() {
        val daily = dailyValues(
            entries = listOf(entry(10, 9, 11)),
            activityId = activity,
            metric = TrendMetric.TIME,
            from = day(9),
            to = day(11),
            nowMillis = now,
        )
        assertEquals(3, daily.size)
        assertEquals(0.0, daily.getValue(day(9)), 1e-9)
        assertEquals(2.0, daily.getValue(day(10)), 1e-9)
        assertEquals(0.0, daily.getValue(day(11)), 1e-9)
    }

    @Test
    fun `two sessions on one day add up`() {
        val daily = dailyValues(
            listOf(entry(10, 9, 10), entry(10, 14, 16)),
            activity, TrendMetric.TIME, day(10), day(10), now,
        )
        assertEquals(3.0, daily.getValue(day(10)), 1e-9)
    }

    @Test
    fun `another activity's entries are not this activity's`() {
        val other = entry(10, 9, 17).copy(activity = 99)
        val daily = dailyValues(
            listOf(other), activity, TrendMetric.TIME, day(10), day(10), now,
        )
        assertEquals(0.0, daily.getValue(day(10)), 1e-9)
    }

    @Test
    fun `a running entry counts up to the time it is asked at`() {
        val running = entry(31, 9, null)
        val daily = dailyValues(
            listOf(running), activity, TrendMetric.TIME, day(31), day(31), now,
        )
        assertEquals(3.0, daily.getValue(day(31)), 1e-9)
    }

    // ---- rolling ----

    @Test
    fun `each point is the mean of its day and the days behind it`() {
        // Three hours on the 10th, nothing else in the week.
        val points = rollingTrend(
            entries = listOf(entry(10, 9, 12)),
            activityId = activity,
            metric = TrendMetric.TIME,
            from = day(10),
            to = day(12),
            window = 3,
            nowMillis = now,
        )
        assertEquals(3, points.size)
        assertEquals(day(10), points[0].date)
        assertEquals(1.0, points[0].value, 1e-9)   // 3h over three days
        assertEquals(1.0, points[1].value, 1e-9)
        assertEquals(1.0, points[2].value, 1e-9)
    }

    @Test
    fun `the first point is a full window, not a ramp`() {
        // An hour every day. A trailing average of a steady habit is flat — it
        // would start at a third of its value if the lead-in were missing.
        val entries = (5..12).map { entry(it, 9, 10) }
        val points = rollingTrend(
            entries, activity, TrendMetric.TIME, day(10), day(12), window = 3, nowMillis = now,
        )
        points.forEach { assertEquals(1.0, it.value, 1e-9) }
    }

    @Test
    fun `the lead-in is the window minus the day itself`() {
        assertEquals(day(8), leadInFrom(day(10), 3))
        assertEquals(day(10), leadInFrom(day(10), 1))
        assertEquals(day(10), leadInFrom(day(10), 0))
    }

    @Test
    fun `a quiet fortnight pulls the average down`() {
        val busy = (1..7).map { entry(it, 9, 13) }        // four hours a day
        val points = rollingTrend(
            busy, activity, TrendMetric.TIME, day(7), day(21), window = 7, nowMillis = now,
        )
        assertEquals(4.0, points.first().value, 1e-9)
        assertEquals(0.0, points.last().value, 1e-9)
        assertTrue("should be falling", points[7].value < points[0].value)
    }

    @Test
    fun `sessions and time are different questions`() {
        // Two short sessions one day, one long session the next.
        val entries = listOf(entry(10, 9, 10), entry(10, 11, 12), entry(11, 9, 15))
        val sessions = rollingTrend(
            entries, activity, TrendMetric.SESSIONS, day(10), day(11), 1, now,
        )
        val time = rollingTrend(
            entries, activity, TrendMetric.TIME, day(10), day(11), 1, now,
        )
        assertEquals(2.0, sessions[0].value, 1e-9)
        assertEquals(1.0, sessions[1].value, 1e-9)
        assertEquals(2.0, time[0].value, 1e-9)
        assertEquals(6.0, time[1].value, 1e-9)
    }

    @Test
    fun `a window longer than the range still averages over the window`() {
        val points = rollingTrend(
            listOf(entry(10, 9, 12)), activity, TrendMetric.TIME,
            day(10), day(10), window = 30, nowMillis = now,
        )
        assertEquals(1, points.size)
        assertEquals(0.1, points[0].value, 1e-9)   // 3h spread over thirty days
    }

    @Test
    fun `a backwards range plots nothing`() {
        assertTrue(
            rollingTrend(emptyList(), activity, TrendMetric.TIME, day(12), day(10), 7, now)
                .isEmpty()
        )
    }

    // ---- the chart around the points ----

    @Test
    fun `the axis ends on a round number above the peak`() {
        val points = listOf(TrendPoint(day(1), 0.0), TrendPoint(day(2), 3.2))
        val chart = trendChart(points, TrendMetric.TIME, 7, day(1), day(2), "reading", activity)
        assertTrue(chart.yMax >= 3.2)
        assertEquals(0.0, chart.yTicks.first(), 1e-9)
        assertEquals(points.size, chart.xLabels.size)
    }

    @Test
    fun `the same selection makes the same signature and a different one does not`() {
        val points = listOf(TrendPoint(day(1), 1.0))
        val a = trendChart(points, TrendMetric.TIME, 7, day(1), day(2), "reading", activity)
        val b = trendChart(points, TrendMetric.TIME, 7, day(1), day(2), "reading", activity)
        val c = trendChart(points, TrendMetric.SESSIONS, 7, day(1), day(2), "reading", activity)
        assertEquals(a.signature, b.signature)
        assertTrue(a.signature != c.signature)
    }

    @Test
    fun `ticks read as numbers a person would write`() {
        assertEquals("2h", formatTick(2.0, TrendMetric.TIME))
        assertEquals("0.5h", formatTick(0.5, TrendMetric.TIME))
        assertEquals("3", formatTick(3.0, TrendMetric.SESSIONS))
    }
}
