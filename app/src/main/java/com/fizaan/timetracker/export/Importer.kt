package com.fizaan.timetracker.export

import com.fizaan.timetracker.data.Activity
import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.parseKimaiLocal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One entry read out of a picked file, ready to be sent to the server. */
data class ImportEntry(
    val begin: LocalDateTime,
    val end: LocalDateTime,
    val activity: String,
    val description: String?,
    val tags: List<String>,
)

/** What a file turned into: the entries it holds, and what was passed over. */
data class ImportRead(
    val entries: List<ImportEntry>,
    /** Rows that named no usable entry — a running timer, an unreadable date. */
    val ignored: Int,
)

/**
 * What an import would do, worked out before a single request is sent.
 *
 * [duplicates] are entries the server already holds; they are counted and left
 * alone, which is what makes importing the same file twice harmless.
 */
data class ImportPlan(
    val entries: List<ImportEntry>,
    val newActivities: List<String>,
    val duplicates: Int,
)

/** The columns an import actually needs; the rest of the export is derived. */
private const val DATE = "date"
private const val BEGIN = "begin"
private const val END = "end"
private const val ACTIVITY = "activity"
private const val DESCRIPTION = "description"
private const val TAGS = "tags"

/**
 * Turn a grid of cells into entries, using the header row to find the columns.
 *
 * Only Date, Begin, End and Activity are required to mean anything — the rest
 * of an export is either derived (Duration, Hours) or the same on every row
 * (Customer, Project), so a file that has lost them is still importable.
 *
 * Rows without an end are skipped rather than imported as running timers: a
 * timer belongs to the device it is running on, and starting one from a file
 * would collide with whatever is actually running now.
 */
fun readEntries(table: List<List<String>>): ImportRead {
    val headerAt = table.indexOfFirst { row ->
        row.any { it.trim().equals(DATE, ignoreCase = true) } &&
            row.any { it.trim().equals(ACTIVITY, ignoreCase = true) }
    }
    require(headerAt >= 0) {
        "That file doesn't look like a timesheet — it has no Date and Activity columns."
    }
    val header = table[headerAt].map { it.trim().lowercase() }
    fun column(name: String) = header.indexOf(name)
    val dateAt = column(DATE)
    val beginAt = column(BEGIN)
    val endAt = column(END)
    val activityAt = column(ACTIVITY)
    require(beginAt >= 0 && endAt >= 0) {
        "That timesheet has no Begin and End columns to read times from."
    }
    val descriptionAt = column(DESCRIPTION)
    val tagsAt = column(TAGS)

    var ignored = 0
    val entries = mutableListOf<ImportEntry>()
    for (row in table.drop(headerAt + 1)) {
        fun cell(at: Int) = if (at in row.indices) row[at].trim() else ""
        val date = parseDate(cell(dateAt))
        val begin = parseTime(cell(beginAt))
        val end = parseTime(cell(endAt))
        val activity = cell(activityAt)
        if (date == null || begin == null || end == null || activity.isEmpty()) {
            ignored++
            continue
        }
        // An entry that ends earlier than it began ran past midnight; the export
        // writes one date and two wall-clock times, so the day is inferred here.
        val from = LocalDateTime.of(date, begin)
        val to = LocalDateTime.of(date, end).let { if (it <= from) it.plusDays(1) else it }
        entries += ImportEntry(
            begin = from,
            end = to,
            activity = activity,
            description = cell(descriptionAt).ifBlank { null },
            tags = cell(tagsAt).split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
    return ImportRead(entries.sortedBy { it.begin }, ignored)
}

/**
 * Match [entries] against what is already stored and say what is left to do.
 *
 * An entry is the same entry when it is the same activity begun at the same
 * minute — the identity a timesheet actually has, since nothing else about a
 * session is unique and the file carries no ids. Two identical rows inside one
 * file collapse the same way, so a file that was itself built from two exports
 * doesn't import twice.
 */
fun planImport(
    entries: List<ImportEntry>,
    existing: List<TimesheetEntry>,
    activities: List<Activity>,
): ImportPlan {
    val names = activities.associate { it.id to it.name.trim().lowercase() }
    val seen = existing.mapNotNullTo(mutableSetOf()) { e ->
        val begin = parseKimaiLocal(e.begin) ?: return@mapNotNullTo null
        key(names[e.activity] ?: "#${e.activity}", begin)
    }
    val known = activities.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
    val fresh = mutableListOf<ImportEntry>()
    val wanted = linkedSetOf<String>()
    var duplicates = 0

    for (entry in entries) {
        if (!seen.add(key(entry.activity.trim().lowercase(), entry.begin))) {
            duplicates++
            continue
        }
        fresh += entry
        val name = entry.activity.trim()
        if (name.lowercase() !in known) wanted += name
    }
    return ImportPlan(entries = fresh, newActivities = wanted.toList(), duplicates = duplicates)
}

private fun key(activity: String, begin: LocalDateTime): String =
    "$activity@${begin.withSecond(0).withNano(0)}"

/** "2026-08-17", or the serial number a spreadsheet turns that into. */
internal fun parseDate(raw: String): LocalDate? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    runCatching { LocalDate.parse(text.take(10)) }.getOrNull()?.let { return it }
    serial(text)?.let { return EXCEL_EPOCH.plusDays(it.toLong()) }
    return null
}

/** "09:30" or "09:30:00", or the fraction of a day a spreadsheet stores. */
internal fun parseTime(raw: String): LocalTime? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.contains(':')) {
        val parts = text.split(':')
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        val s = parts.getOrNull(2)?.trim()?.substringBefore('.')?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
        return LocalTime.of(h, m, s)
    }
    val fraction = serial(text) ?: return null
    val day = fraction - Math.floor(fraction)
    return LocalTime.ofSecondOfDay(Math.round(day * 86_400).coerceIn(0, 86_399))
}

private fun serial(text: String): Double? = text.toDoubleOrNull()?.takeIf { it > 0 }

/**
 * Day 1 in a spreadsheet is 1900-01-01, counted as though 1900 were a leap year
 * — so from 1900-03-01 on, the serial is one greater than the real elapsed day
 * count. Anchoring on 1899-12-30 absorbs both, which is what every spreadsheet
 * does too.
 */
private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
