package com.fizaan.timetracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The overlap rules, stated as arithmetic. Times are whole minutes from an
 * arbitrary zero; results are asserted in minutes for legibility.
 */
class OverlapTest {

    private fun span(fromMin: Long, toMin: Long, tag: String) =
        TaggedSpan(fromMin * 60_000L, toMin * 60_000L, tag)

    private fun minutes(spans: List<TaggedSpan>): Map<String, Long> =
        resolveTagMillis(spans).mapValues { it.value / 60_000L }

    @Test
    fun `no overlap leaves every span whole`() {
        val got = minutes(
            listOf(
                span(0, 60, "productive"),
                span(60, 90, "unproductive"),
            )
        )
        assertEquals(mapOf("productive" to 60L, "unproductive" to 30L), got)
    }

    @Test
    fun `identical spans collapse onto the higher rank`() {
        val got = minutes(
            listOf(
                span(0, 60, "unproductive"),
                span(0, 60, "productive"),
            )
        )
        assertEquals(mapOf("productive" to 60L), got)
    }

    @Test
    fun `staggered overlap splits at the intersection`() {
        // 0 ────── unproductive ────── 60
        //        15 ── productive ── 45
        val got = minutes(
            listOf(
                span(0, 60, "unproductive"),
                span(15, 45, "productive"),
            )
        )
        assertEquals(mapOf("productive" to 30L, "unproductive" to 30L), got)
    }

    @Test
    fun `a lower rank keeps the tail it holds alone`() {
        // 0 ── productive ── 30
        //          20 ── unproductive ── 50
        val got = minutes(
            listOf(
                span(0, 30, "productive"),
                span(20, 50, "unproductive"),
            )
        )
        assertEquals(mapOf("productive" to 30L, "unproductive" to 20L), got)
    }

    @Test
    fun `semi-productive sits between the two`() {
        val got = minutes(
            listOf(
                span(0, 60, "unproductive"),
                span(0, 40, "semi-productive"),
                span(0, 20, "productive"),
            )
        )
        assertEquals(
            mapOf("productive" to 20L, "semi-productive" to 20L, "unproductive" to 20L),
            got,
        )
    }

    @Test
    fun `two spans of one tag never count a minute twice`() {
        val got = minutes(
            listOf(
                span(0, 60, "productive"),
                span(30, 90, "productive"),
            )
        )
        assertEquals(mapOf("productive" to 90L), got)
    }

    @Test
    fun `untagged loses to every classification`() {
        val got = minutes(
            listOf(
                span(0, 60, ""),
                span(0, 30, "unproductive"),
            )
        )
        assertEquals(mapOf("" to 30L, "unproductive" to 30L), got)
    }

    @Test
    fun `life things overlaps freely and takes nothing`() {
        // A walk logged across a work session: both keep their full length.
        val got = minutes(
            listOf(
                span(0, 60, LIFE_THINGS),
                span(15, 45, "productive"),
            )
        )
        assertEquals(mapOf(LIFE_THINGS to 60L, "productive" to 30L), got)
    }

    @Test
    fun `life things is still deduplicated against itself`() {
        val got = minutes(
            listOf(
                span(0, 60, LIFE_THINGS),
                span(30, 90, LIFE_THINGS),
            )
        )
        assertEquals(mapOf(LIFE_THINGS to 90L), got)
    }

    @Test
    fun `a contained span disappears under a longer better one`() {
        val got = minutes(
            listOf(
                span(0, 120, "productive"),
                span(30, 60, "unproductive"),
            )
        )
        assertEquals(mapOf("productive" to 120L), got)
    }

    @Test
    fun `equal ranks go to whichever started first`() {
        val got = minutes(
            listOf(
                span(0, 60, "reading"),
                span(0, 60, "chores"),
            )
        )
        // Same rank, same start: the tie-break is the tag name, and it must be
        // one of them entirely — never both.
        assertEquals(mapOf("chores" to 60L), got)
    }

    @Test
    fun `gaps between spans are not counted`() {
        val got = minutes(
            listOf(
                span(0, 30, "productive"),
                span(90, 120, "productive"),
            )
        )
        assertEquals(mapOf("productive" to 60L), got)
    }

    @Test
    fun `an empty list resolves to nothing`() {
        assertEquals(emptyMap<String, Long>(), resolveTagMillis(emptyList()))
    }

    @Test
    fun `zero-length spans are dropped`() {
        val got = minutes(listOf(span(10, 10, "productive"), span(0, 30, "unproductive")))
        assertEquals(mapOf("unproductive" to 30L), got)
    }

    @Test
    fun `resolved time never exceeds the wall clock it covers`() {
        val spans = listOf(
            span(0, 100, "productive"),
            span(20, 60, "unproductive"),
            span(50, 140, "semi-productive"),
            span(200, 260, ""),
        )
        // Union of the classified spans: 0–140 and 200–260.
        assertEquals(200L, minutes(spans).values.sum())
    }
}
