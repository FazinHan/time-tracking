package com.fizaan.timetracker.data

import com.fizaan.timetracker.util.epochMillis
import com.fizaan.timetracker.util.formatKimai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The queue's arithmetic: what a screen sees once what's waiting on the device
 * is folded into what the server said.
 */
class PendingQueueTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): String =
        formatKimai(LocalDateTime.of(2026, 8, day, hour, minute))

    private fun millis(day: Int, hour: Int, minute: Int = 0): Long =
        epochMillis(LocalDateTime.of(2026, 8, day, hour, minute))

    private fun serverEntry(
        id: Int,
        day: Int,
        fromHour: Int,
        toHour: Int?,
        activity: Int = 7,
    ) = TimesheetEntry(
        id = id,
        begin = at(day, fromHour),
        end = toHour?.let { at(day, it) },
        duration = toHour?.let { (it - fromHour) * 3600L } ?: 0L,
        activity = activity,
        project = 1,
    )

    private val wholeDay = millis(2, 0) to millis(3, 0)

    @Test
    fun `an empty queue changes nothing`() {
        val entries = listOf(serverEntry(1, 2, 9, 10))
        val out = mergePending(entries, PendingQueue(), 1, wholeDay.first, wholeDay.second)
        assertEquals(entries, out)
    }

    @Test
    fun `a queued start appears as an entry of its own`() {
        val queue = PendingQueue(
            starts = listOf(
                PendingStart(localId = -1, activityId = 7, beginIso = at(2, 11), tags = "productive"),
            ),
        )
        val out = mergePending(
            listOf(serverEntry(1, 2, 9, 10)), queue, 1, wholeDay.first, wholeDay.second,
        )
        assertEquals(2, out.size)
        val queued = out.last()
        assertEquals(-1, queued.id)
        assertEquals(7, queued.activity)
        assertNull(queued.end)
        assertEquals(listOf("productive"), queued.tags)
    }

    @Test
    fun `a queued start outside the window is left out of it`() {
        val queue = PendingQueue(
            starts = listOf(PendingStart(localId = -1, activityId = 7, beginIso = at(5, 11))),
        )
        val out = mergePending(emptyList(), queue, 1, wholeDay.first, wholeDay.second)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a queued stop ends the server entry it names, at the time it happened`() {
        val queue = PendingQueue(
            stops = listOf(
                PendingStop(entryId = 1, beginIso = at(2, 9), endIso = at(2, 11, 30)),
            ),
        )
        val out = mergePending(
            listOf(serverEntry(1, 2, 9, null)), queue, 1, wholeDay.first, wholeDay.second,
        )
        assertEquals(1, out.size)
        assertEquals(at(2, 11, 30), out.first().end)
        assertEquals(2 * 3600L + 30 * 60L, out.first().duration)
    }

    @Test
    fun `a stop for an entry outside the window touches nothing`() {
        val entries = listOf(serverEntry(1, 2, 9, 10))
        val queue = PendingQueue(
            stops = listOf(PendingStop(entryId = 99, beginIso = at(2, 9), endIso = at(2, 11))),
        )
        val out = mergePending(entries, queue, 1, wholeDay.first, wholeDay.second)
        assertEquals(entries, out)
    }

    @Test
    fun `a queued start that was also stopped carries its own length`() {
        val queue = PendingQueue(
            starts = listOf(
                PendingStart(localId = -1, activityId = 7, beginIso = at(2, 14), endIso = at(2, 15, 30)),
            ),
        )
        val out = mergePending(emptyList(), queue, 1, wholeDay.first, wholeDay.second)
        assertEquals(90 * 60L, out.single().duration)
    }

    @Test
    fun `merged entries come back in time order`() {
        val queue = PendingQueue(
            starts = listOf(
                PendingStart(localId = -1, activityId = 7, beginIso = at(2, 8)),
                PendingStart(localId = -2, activityId = 8, beginIso = at(2, 20)),
            ),
        )
        val out = mergePending(
            listOf(serverEntry(1, 2, 12, 13)), queue, 1, wholeDay.first, wholeDay.second,
        )
        assertEquals(listOf(-1, 1, -2), out.map { it.id })
    }

    @Test
    fun `only negative ids are queued ones`() {
        assertTrue(isQueued(-1))
        assertFalse(isQueued(0))
        assertFalse(isQueued(4211))
    }

    @Test
    fun `a queued entry keeps the project everything else is filed under`() {
        val entry = PendingStart(localId = -1, activityId = 7, beginIso = at(2, 9)).asEntry(42)
        assertEquals(42, entry.project)
    }

    @Test
    fun `a queued entry expands with the name of its activity`() {
        val activities = listOf(Activity(id = 7, name = "reading"))
        val active = PendingStart(localId = -1, activityId = 7, beginIso = at(2, 9))
            .asEntry(1)
            .asActive(activities)
        assertEquals("reading", active.activity?.name)
        assertEquals(-1, active.id)
    }

    @Test
    fun `an unknown activity still expands, without a name`() {
        val active = PendingStart(localId = -1, activityId = 7, beginIso = at(2, 9))
            .asEntry(1)
            .asActive(emptyList())
        assertEquals(7, active.activity?.id)
        assertNull(active.activity?.name)
    }

    @Test
    fun `a stopped queued start no longer counts as running`() {
        val queue = PendingQueue(
            starts = listOf(
                PendingStart(localId = -1, activityId = 7, beginIso = at(2, 9), endIso = at(2, 10)),
                PendingStart(localId = -2, activityId = 8, beginIso = at(2, 11)),
            ),
        )
        assertEquals(listOf(-2), queue.running.map { it.localId })
        assertEquals(2, queue.size)
    }

    @Test
    fun `the window's far edge is exclusive, like the server's own`() {
        val queue = PendingQueue(
            starts = listOf(
                PendingStart(localId = -1, activityId = 7, beginIso = at(3, 0)),
                PendingStart(localId = -2, activityId = 7, beginIso = at(2, 0)),
            ),
        )
        val out = mergePending(emptyList(), queue, 1, wholeDay.first, wholeDay.second)
        assertEquals(listOf(-2), out.map { it.id })
    }
}
