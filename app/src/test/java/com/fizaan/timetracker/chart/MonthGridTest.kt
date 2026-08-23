package com.fizaan.timetracker.chart

import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.epochMillis
import com.fizaan.timetracker.util.formatKimai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * The calendar's layout arithmetic, which is the part of it that can be wrong
 * without looking wrong: a month drawn one column out is still a tidy grid, and
 * a leap February that loses its 29th still fills the screen.
 */
class MonthGridTest {

    private val activity = 4
    private val august = YearMonth.of(2026, 8)
    private val now = epochMillis(LocalDateTime.of(2026, 8, 31, 12, 0))
    private val today = LocalDate.of(2026, 8, 23)

    private fun entry(date: LocalDate, hour: Int, endHour: Int?, id: Int = hour) = TimesheetEntry(
        id = id + date.dayOfMonth * 100,
        begin = formatKimai(date.atTime(hour, 0)),
        end = endHour?.let { formatKimai(date.atTime(it, 0)) },
        duration = null,
        description = null,
        tags = null,
        activity = activity,
        project = 1,
    )

    private fun chart(entries: List<TimesheetEntry>, month: YearMonth = august) = monthChart(
        entries = entries,
        activityId = activity,
        month = month,
        title = "reading",
        today = today,
        nowMillis = now,
    )

    // ---- where the 1st lands ----

    @Test
    fun `a February beginning on a Monday needs four rows`() {
        val feb = YearMonth.of(2021, 2)
        assertEquals(0, firstColumnOf(feb, DayOfWeek.MONDAY))
        assertEquals(4, rowsFor(feb, DayOfWeek.MONDAY))
    }

    @Test
    fun `a leap February keeps its extra day`() {
        val feb = YearMonth.of(2024, 2)
        assertEquals(3, firstColumnOf(feb, DayOfWeek.MONDAY))
        assertEquals(5, rowsFor(feb, DayOfWeek.MONDAY))
        assertEquals(29, chart(emptyList(), feb).cells.size)
    }

    @Test
    fun `a month beginning on a Saturday spills into six rows`() {
        val may = YearMonth.of(2021, 5)
        assertEquals(5, firstColumnOf(may, DayOfWeek.MONDAY))
        assertEquals(6, rowsFor(may, DayOfWeek.MONDAY))
    }

    @Test
    fun `starting the week on Sunday moves every column along`() {
        val may = YearMonth.of(2021, 5)
        assertEquals(6, firstColumnOf(may, DayOfWeek.SUNDAY))
        val feb = YearMonth.of(2021, 2)
        assertEquals(1, firstColumnOf(feb, DayOfWeek.SUNDAY))
        assertEquals(5, rowsFor(feb, DayOfWeek.SUNDAY))
    }

    @Test
    fun `the first day sits in the first row at its own column`() {
        val c = chart(emptyList())
        assertEquals(0 to c.firstColumn, cellPosition(c, 0))
        // The day that starts the second week is column zero of row one.
        val toSecondRow = 7 - c.firstColumn
        assertEquals(1 to 0, cellPosition(c, toSecondRow))
    }

    // ---- counting ----

    @Test
    fun `a day is as dark as it was busy`() {
        val third = august.atDay(3)
        val fourth = august.atDay(4)
        val c = chart(
            listOf(
                entry(third, 9, 10), entry(third, 11, 12), entry(third, 14, 15),
                entry(fourth, 9, 10),
            )
        )
        assertEquals(3, c.busiest)
        assertEquals(3, c.cells[2].sessions)
        assertEquals(1.0f, c.cells[2].intensity, 1e-6f)
        assertEquals(1, c.cells[3].sessions)
        assertTrue("the quietest active day must still show", c.cells[3].intensity > 0f)
        assertTrue(c.cells[3].intensity < c.cells[2].intensity)
        assertEquals(0.0f, c.cells[0].intensity, 1e-6f)
    }

    @Test
    fun `a month with one session everywhere is evenly shaded`() {
        val c = chart(listOf(entry(august.atDay(1), 9, 10)))
        assertEquals(1, c.busiest)
        assertEquals(1.0f, c.cells[0].intensity, 1e-6f)
    }

    @Test
    fun `the shading spreads across the month's own range, not from one session`() {
        // Three, five and seven sessions: the quietest active day is the palest
        // even though three sessions is not one.
        val days = listOf(august.atDay(1) to 3, august.atDay(2) to 5, august.atDay(3) to 7)
        val entries = days.flatMap { (date, count) ->
            (0 until count).map { entry(date, 8 + it, 9 + it, id = it) }
        }
        val c = chart(entries)
        assertEquals(7, c.busiest)
        assertEquals(0.25f, c.cells[0].intensity, 1e-6f)   // three, the quietest
        assertEquals(0.625f, c.cells[1].intensity, 1e-6f)  // five, halfway
        assertEquals(1.0f, c.cells[2].intensity, 1e-6f)    // seven, the busiest
    }

    @Test
    fun `a month of equally busy days is all one shade`() {
        val entries = listOf(august.atDay(4), august.atDay(9)).flatMap { date ->
            (0 until 3).map { entry(date, 8 + it, 9 + it, id = it) }
        }
        val c = chart(entries)
        assertEquals(1.0f, c.cells[3].intensity, 1e-6f)
        assertEquals(1.0f, c.cells[8].intensity, 1e-6f)
        assertEquals(0f, c.cells[4].intensity, 1e-6f)
    }

    @Test
    fun `a day with nothing on it is off the scale entirely`() {
        assertEquals(0f, intensityOf(0, 1, 4), 1e-6f)
        assertEquals(0.25f, intensityOf(1, 1, 4), 1e-6f)
        assertEquals(1f, intensityOf(4, 1, 4), 1e-6f)
    }

    @Test
    fun `the neighbouring months are somebody else's problem`() {
        val c = chart(
            listOf(
                entry(LocalDate.of(2026, 7, 31), 9, 10),
                entry(august.atDay(1), 9, 10),
                entry(LocalDate.of(2026, 9, 1), 9, 10),
            )
        )
        assertEquals(1, c.totalSessions)
        assertEquals(1, c.activeDays)
    }

    @Test
    fun `an empty month says so rather than dividing by nothing`() {
        val c = chart(emptyList())
        assertEquals(0, c.totalSessions)
        assertEquals(0, c.busiest)
        assertEquals(31, c.cells.size)
        assertTrue(c.cells.all { it.intensity == 0f })
    }

    @Test
    fun `time is totalled alongside the count`() {
        val c = chart(listOf(entry(august.atDay(2), 9, 11), entry(august.atDay(2), 13, 14)))
        assertEquals(3 * 3600L, c.cells[1].seconds)
        assertEquals(3 * 3600L, c.totalSeconds)
    }

    @Test
    fun `today is marked only in the month it is in`() {
        assertEquals(22, chart(emptyList()).todayIndex)
        assertNull(chart(emptyList(), YearMonth.of(2026, 7)).todayIndex)
    }

    @Test
    fun `the week has seven headings`() {
        assertEquals(7, chart(emptyList()).weekdayLabels.size)
    }
}
