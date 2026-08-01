package com.fizaan.timetracker.util

/**
 * Resolving parallel entries onto a single timeline.
 *
 * Two timers can run at once, and when they do, summing their durations counts
 * the same wall-clock minute twice — a stretch of overlap between something
 * productive and something unproductive would be scored as both. The fix is to
 * treat time as the scarce thing it is: every instant belongs to exactly one
 * tag, the most productive one running at that instant.
 *
 * Only the intersection is contested. Where an entry is the sole occupant of a
 * stretch, that stretch is its own regardless of rank, so a lower-ranked entry
 * still keeps everything outside the overlap.
 */

/** The tag that opts out of the whole business; see [resolveTagMillis]. */
const val LIFE_THINGS = "life things"

/**
 * Precedence among the classification tags. Everything else — untagged
 * included — sits at the bottom and loses to all three.
 */
fun tagRank(tag: String): Int = when (tag.lowercase()) {
    "productive" -> 3
    "semi-productive" -> 2
    "unproductive" -> 1
    else -> 0
}

/** A tagged stretch of wall clock. [endMs] is exclusive and never before [startMs]. */
data class TaggedSpan(val startMs: Long, val endMs: Long, val tag: String) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Total milliseconds per tag with overlaps resolved by [tagRank].
 *
 * 'life things' is deliberately exempt: it isn't a productivity judgement, so
 * it neither takes time from a classified entry nor loses any to one — a walk
 * logged over a work session leaves both intact. It is still de-duplicated
 * against itself, so it can never exceed the clock on its own account.
 *
 * Equal ranks (two productive entries at once, or two unrelated tags) can't
 * both hold an instant either; the one that started earlier keeps it, with the
 * tag name as a final tie-break so the result never depends on input order.
 */
fun resolveTagMillis(spans: List<TaggedSpan>): Map<String, Long> {
    val usable = spans.filter { it.lengthMs > 0 }
    val (exempt, contested) = usable.partition { it.tag.equals(LIFE_THINGS, ignoreCase = true) }
    val out = mutableMapOf<String, Long>()

    exempt.groupBy { it.tag }.forEach { (tag, list) -> out[tag] = unionMillis(list) }

    if (contested.isEmpty()) return out

    // Sweep the boundaries in order. Between two consecutive boundaries the set
    // of live spans cannot change, so one winner takes the whole gap.
    val byStart = contested.sortedBy { it.startMs }
    val bounds = contested.flatMap { listOf(it.startMs, it.endMs) }.distinct().sorted()
    val live = mutableListOf<TaggedSpan>()
    var next = 0
    for (i in 0 until bounds.size - 1) {
        val from = bounds[i]
        val to = bounds[i + 1]
        while (next < byStart.size && byStart[next].startMs <= from) live += byStart[next++]
        live.removeAll { it.endMs <= from }
        val winner = live.maxWithOrNull(SpanPrecedence) ?: continue
        out[winner.tag] = (out[winner.tag] ?: 0L) + (to - from)
    }
    return out
}

/** Highest rank wins; then the earlier start; then the lower tag name. */
private val SpanPrecedence: Comparator<TaggedSpan> =
    compareBy<TaggedSpan> { tagRank(it.tag) }
        .thenByDescending { it.startMs }
        .thenByDescending { it.tag }

/** Length of the union of [spans] — overlapping stretches counted once. */
fun unionMillis(spans: List<TaggedSpan>): Long {
    if (spans.isEmpty()) return 0L
    val sorted = spans.sortedBy { it.startMs }
    var total = 0L
    var start = sorted.first().startMs
    var end = sorted.first().endMs
    for (span in sorted.drop(1)) {
        if (span.startMs > end) {
            total += end - start
            start = span.startMs
            end = span.endMs
        } else if (span.endMs > end) {
            end = span.endMs
        }
    }
    return total + (end - start)
}
