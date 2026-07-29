package com.fizaan.timetracker.pomodoro

/**
 * The pomodoro schedule.
 *
 * A session is a fixed alternation of work and break periods that follows
 * automatically from when it started, so nothing here has to be ticked or
 * stored: given the start time and these lengths, the phase at any instant is
 * pure arithmetic. That also means a killed process, a locked screen or a
 * missed alarm can never desynchronise the timer.
 */
data class PomodoroSettings(
    val workMinutes: Int = 25,
    val breakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    /** Short breaks taken before one of them is replaced by a long break. */
    val breaksBeforeLong: Int = 3,
) {
    /** Guards the arithmetic below against a zero-length (infinite) cycle. */
    fun sane() = PomodoroSettings(
        workMinutes = workMinutes.coerceIn(1, 180),
        breakMinutes = breakMinutes.coerceIn(1, 120),
        longBreakMinutes = longBreakMinutes.coerceIn(1, 180),
        breaksBeforeLong = breaksBeforeLong.coerceIn(1, 12),
    )
}

enum class Phase { WORK, BREAK, LONG_BREAK }

val Phase.isBreak: Boolean get() = this != Phase.WORK

/** Where a session stands right now. [index] counts every period from 0. */
data class PhaseSlot(
    val kind: Phase,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    /** 1-based ordinal among periods of the same kind — "Work 3", "Break 2". */
    val ordinal: Int,
)

/**
 * The endless period sequence: work, break, work, break … with every
 * (breaksBeforeLong + 1)-th break stretched into a long one.
 */
private fun kinds(s: PomodoroSettings): Sequence<Phase> = sequence {
    var breaks = 0
    while (true) {
        yield(Phase.WORK)
        breaks++
        yield(if (breaks % (s.breaksBeforeLong + 1) == 0) Phase.LONG_BREAK else Phase.BREAK)
    }
}

fun Phase.lengthMs(s: PomodoroSettings): Long = when (this) {
    Phase.WORK -> s.workMinutes
    Phase.BREAK -> s.breakMinutes
    Phase.LONG_BREAK -> s.longBreakMinutes
} * 60_000L

/**
 * How much of each period was cut off by tapping skip, keyed by period index.
 *
 * A skip doesn't move the session's real start — that is what the Kimai entry
 * is stamped with — it moves the *schedule* forward by the unused remainder, so
 * the phase stays a pure function of an instant and still self-corrects after
 * the app has been away. These are the amounts by which the two have parted.
 */
typealias PomodoroSkips = Map<Int, Long>

/** The period covering [nowMs] in a session whose schedule began at [startMs]. */
fun phaseAt(startMs: Long, nowMs: Long, raw: PomodoroSettings): PhaseSlot {
    val s = raw.sane()
    val elapsed = (nowMs - startMs).coerceAtLeast(0)
    var cursor = 0L
    var index = 0
    val seen = mutableMapOf<Phase, Int>()
    for (kind in kinds(s)) {
        val len = kind.lengthMs(s)
        val ordinal = (seen[kind] ?: 0) + 1
        seen[kind] = ordinal
        if (elapsed < cursor + len) {
            return PhaseSlot(kind, index, startMs + cursor, startMs + cursor + len, ordinal)
        }
        cursor += len
        index++
    }
    error("unreachable")   // kinds() never ends
}

/**
 * A one-line-per-fact account of a finished session, for the entry's
 * description — the only place this detail is kept, since the whole session is
 * logged to Kimai as a single unbroken entry.
 */
fun sessionSummary(
    startMs: Long,
    stopMs: Long,
    raw: PomodoroSettings,
    /** Period index → the time cut off it by a skip; see [PomodoroSkips]. */
    skips: Map<Int, Long> = emptyMap(),
): String {
    val s = raw.sane()
    val total = (stopMs - startMs).coerceAtLeast(0)
    // Skipping ends a period early, which is the same as the schedule running
    // ahead of the clock: the nominal timeline covers the real elapsed time plus
    // everything skipped off it. Periods are counted along that timeline, but
    // what each one is credited with is the time actually spent in it.
    val nominal = total + skips.values.sum()
    var cursor = 0L
    var index = 0
    val counts = mutableMapOf<Phase, Int>()
    val spent = mutableMapOf<Phase, Long>()
    for (kind in kinds(s)) {
        if (cursor >= nominal) break
        val len = kind.lengthMs(s).coerceAtMost(nominal - cursor)
        counts[kind] = (counts[kind] ?: 0) + 1
        spent[kind] = (spent[kind] ?: 0) + (len - (skips[index] ?: 0L)).coerceAtLeast(0L)
        cursor += len
        index++
    }
    fun line(kind: Phase, label: String): String? {
        val n = counts[kind] ?: return null
        return "$label: $n (${fmt(spent[kind] ?: 0)})"
    }
    return listOfNotNull(
        "Pomodoro — total ${fmt(total)}",
        line(Phase.WORK, "Work periods"),
        line(Phase.BREAK, "Short breaks"),
        line(Phase.LONG_BREAK, "Long breaks"),
        if (skips.isEmpty()) null
        else "Ended early: ${skips.size} (${fmt(skips.values.sum())} skipped)",
        "Lengths: work ${s.workMinutes}m · break ${s.breakMinutes}m · " +
            "long break ${s.longBreakMinutes}m after ${s.breaksBeforeLong} " +
            if (s.breaksBeforeLong == 1) "break" else "breaks",
    ).joinToString("\n")
}

/** "1h 05m" / "25m" / "40s", for the summary above. */
private fun fmt(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return when {
        h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
        m > 0 -> "${m}m"
        else -> "${sec}s"
    }
}

/** mm:ss (or h:mm:ss past an hour) countdown label. */
fun formatRemaining(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
