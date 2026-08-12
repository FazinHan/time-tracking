package com.fizaan.timetracker.export

import com.fizaan.timetracker.data.Activity
import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.entrySeconds
import com.fizaan.timetracker.util.parseKimaiLocal
import java.time.format.DateTimeFormatter

/**
 * What an export can be written as.
 *
 * The four Kimai's own dashboard offers. [PRINT] is the odd one out: it renders
 * the same page as [PDF] but hands it to Android's print dialog instead of
 * saving it, which is what "print" means on a phone — the dialog itself offers
 * "save as PDF" for anyone who wanted the file after all.
 */
enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    CSV("CSV", "csv", "text/csv"),
    EXCEL("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("PDF", "pdf", "application/pdf"),
    PRINT("Print", "pdf", "application/pdf"),
}

/** One timesheet entry, flattened into the columns every format shares. */
data class ExportRow(
    val date: String,
    val begin: String,
    val end: String,
    val duration: String,
    /** Hours as a number, so a spreadsheet can add the column up. */
    val hours: Double,
    val customer: String,
    val project: String,
    val activity: String,
    val description: String,
    val tags: String,
)

val ExportColumns = listOf(
    "Date", "Begin", "End", "Duration", "Hours",
    "Customer", "Project", "Activity", "Description", "Tags",
)

fun ExportRow.cells(): List<String> = listOf(
    date, begin, end, duration, hours.toString(),
    customer, project, activity, description, tags,
)

private val DateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TimeFmt = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Flatten a window of entries for export, oldest first.
 *
 * A still-running entry keeps its blank end and counts up to [nowMillis], the
 * same as it does everywhere else in the app — leaving it out would make the
 * totals disagree with the screen it was exported from.
 */
fun exportRows(
    entries: List<TimesheetEntry>,
    activities: List<Activity>,
    customer: String,
    project: String,
    nowMillis: Long = System.currentTimeMillis(),
): List<ExportRow> {
    val names = activities.associate { it.id to it.name }
    return entries
        .sortedBy { parseKimaiLocal(it.begin) }
        .map { e ->
            val begin = parseKimaiLocal(e.begin)
            val end = parseKimaiLocal(e.end)
            val seconds = entrySeconds(e.begin, e.end, e.duration, nowMillis)
            ExportRow(
                date = begin?.toLocalDate()?.format(DateFmt).orEmpty(),
                begin = begin?.format(TimeFmt).orEmpty(),
                end = end?.format(TimeFmt).orEmpty(),
                duration = "%d:%02d".format(seconds / 3600, (seconds % 3600) / 60),
                hours = Math.round(seconds / 3600.0 * 100) / 100.0,
                customer = customer,
                project = project,
                activity = names[e.activity] ?: "#${e.activity}",
                description = e.description.orEmpty(),
                tags = e.tags.orEmpty().joinToString(", "),
            )
        }
}
