package com.fizaan.timetracker.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry both charts are drawn from.
 *
 * It lives outside the renderer precisely so it can be checked here: a Paint is
 * a stub in a unit test, so anything decided next to one is decided in the dark.
 */
class ChartLayoutTest {

    /** Roughly what a proportional font does, and enough to pick a stride by. */
    private val measure = TextWidth { it.length * 6f }

    // ---- axes ----

    @Test
    fun `an axis ends on a round number above the data`() {
        val ticks = niceTicks(3.2)
        assertEquals(0.0, ticks.first(), 1e-9)
        assertTrue(ticks.last() >= 3.2)
        assertEquals(4.0, ticks.last(), 1e-9)
    }

    @Test
    fun `quarter steps stay quarters`() {
        // A peak just over four fifths asks for a step of 0.25. Rounding those
        // ticks to one decimal moved the gridlines to 0.2 and 0.8.
        val ticks = niceTicks(0.82)
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), ticks)
        assertEquals(2, tickDecimals(ticks))
    }

    @Test
    fun `the ticks are evenly spaced whatever the peak`() {
        listOf(0.3, 0.82, 1.0, 3.2, 7.5, 40.0, 365.0).forEach { peak ->
            val ticks = niceTicks(peak)
            val gaps = ticks.zipWithNext { a, b -> b - a }
            gaps.forEach { assertEquals("peak $peak", gaps.first(), it, 1e-9) }
            assertTrue("peak $peak", ticks.last() >= peak)
        }
    }

    @Test
    fun `labels carry only the decimals they need`() {
        assertEquals(0, tickDecimals(listOf(0.0, 1.0, 2.0)))
        assertEquals(1, tickDecimals(listOf(0.0, 0.5, 1.0)))
        assertEquals(2, tickDecimals(listOf(0.0, 0.25, 0.5)))
    }

    @Test
    fun `small values keep their decimals`() {
        val ticks = niceTicks(0.4)
        assertTrue(ticks.last() >= 0.4)
        assertTrue("no floating-point dust", ticks.all { it == Math.round(it * 1000) / 1000.0 })
    }

    @Test
    fun `an empty chart still has an axis`() {
        assertEquals(listOf(0.0, 1.0), niceTicks(0.0))
    }

    // ---- points ----

    @Test
    fun `the first point sits on the axis and the last at the far edge`() {
        assertEquals(10f, xOf(0, 5, 10f, 110f), 1e-4f)
        assertEquals(110f, xOf(4, 5, 10f, 110f), 1e-4f)
        assertEquals(60f, xOf(2, 5, 10f, 110f), 1e-4f)
    }

    @Test
    fun `a single point is centred rather than pinned to the corner`() {
        assertEquals(60f, xOf(0, 1, 10f, 110f), 1e-4f)
    }

    @Test
    fun `zero is on the floor and the maximum at the ceiling`() {
        assertEquals(100f, yOf(0.0, 4.0, 0f, 100f), 1e-4f)
        assertEquals(0f, yOf(4.0, 4.0, 0f, 100f), 1e-4f)
        assertEquals(50f, yOf(2.0, 4.0, 0f, 100f), 1e-4f)
    }

    // ---- the reveal ----

    @Test
    fun `the line grows from nothing to all of it`() {
        assertEquals(0, revealOf(10, 0f).drawn)
        assertEquals(10, revealOf(10, 1f).drawn)
        assertEquals(5, revealOf(10, 0.5f).drawn)
    }

    @Test
    fun `the leading segment is drawn part-way`() {
        val half = revealOf(10, 0.55f)
        assertEquals(5, half.drawn)
        assertEquals(0.5f, half.tail, 1e-4f)
        assertEquals(0f, revealOf(10, 1f).tail, 1e-6f)
    }

    @Test
    fun `a short series is watchable and a long one is not a chore`() {
        assertTrue(revealMillis(30) in 450..1400)
        assertEquals(1400, revealMillis(365))
        assertTrue(revealMillis(30) < revealMillis(365))
    }

    // ---- x labels ----

    @Test
    fun `every label is printed when they all fit`() {
        val labels = List(5) { "3 Aug" }
        assertEquals(listOf(0, 1, 2, 3, 4), pickLabelIndices(labels, 60f, 6f, measure))
    }

    @Test
    fun `a crowded axis is thinned to a calendar-sized stride`() {
        val labels = List(30) { "12 Aug" }
        val picked = pickLabelIndices(labels, 12f, 6f, measure)
        val strides = picked.zipWithNext { a, b -> b - a }.distinct()
        assertEquals(listOf(7), strides)
        assertEquals(29, picked.last())          // anchored on the newest day
    }

    @Test
    fun `a year is labelled monthly rather than daily`() {
        val labels = List(365) { "12 Aug" }
        val picked = pickLabelIndices(labels, 2.2f, 6f, measure)
        val stride = picked[1] - picked[0]
        assertTrue("stride was $stride", stride >= 28)
        assertTrue("no more labels than fit", picked.size <= 14)
    }

    @Test
    fun `nothing to label is not a crash`() {
        assertTrue(pickLabelIndices(emptyList(), 10f, 6f, measure).isEmpty())
        assertEquals(listOf(0), pickLabelIndices(listOf("3 Aug"), 10f, 6f, measure))
    }

    // ---- boxes ----

    @Test
    fun `the plot leaves room for the labels around it`() {
        val spec = ChartSpec(400f, 240f, u = 3f)
        val m = metricsFor(spec, yLabelWidth = 30f, xLabelHeight = 12f, hasTitle = true)
        assertTrue(m.left >= 30f)
        assertTrue(m.bottom < spec.height)
        assertTrue(m.plotWidth > 0f)
        assertTrue(m.plotHeight > 0f)
    }

    @Test
    fun `a gutter never eats the plot, however long the labels`() {
        val spec = ChartSpec(400f, 240f, u = 3f)
        val m = metricsFor(spec, yLabelWidth = 900f, xLabelHeight = 900f, hasTitle = true)
        assertTrue(m.left <= spec.width * 0.32f)
        assertTrue(m.plotHeight > 0f)
    }

    @Test
    fun `calendar cells are square and centred`() {
        val spec = ChartSpec(842f, 595f, u = 1f)
        val m = metricsFor(spec, 0f, 20f, hasTitle = true)
        val geo = gridGeometry(spec, m, MaxWeekRows, headerHeight = 16f)
        assertTrue(geo.cell > 0f)
        assertTrue("grid fits across", geo.originX + geo.cell * 7 <= spec.width + 0.01f)
        assertTrue("grid fits down", geo.originY + geo.cell * MaxWeekRows <= m.bottom + 0.01f)
    }
}
