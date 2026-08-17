package com.fizaan.timetracker.export

import com.fizaan.timetracker.data.Activity
import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.formatKimai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading a timesheet back in.
 *
 * The round trips matter most — a file this app wrote is the one it is likeliest
 * to be handed — but so does everything a spreadsheet does to that file on the
 * way past: strings move into a shared table, dates become numbers, and a
 * European locale saves CSV with semicolons.
 */
class ImportTest {

    private val activities = listOf(
        Activity(id = 4, name = "reading"),
        Activity(id = 7, name = "Writing"),
    )

    private fun row(
        date: String = "2026-08-03",
        begin: String = "09:00",
        end: String = "10:30",
        activity: String = "reading",
        description: String = "",
        tags: String = "",
    ) = ExportRow(
        date = date, begin = begin, end = end, duration = "1:30", hours = 1.5,
        customer = "fizaan", project = "real life", activity = activity,
        description = description, tags = tags,
    )

    private fun entries(vararg rows: ExportRow, asExcel: Boolean = false) =
        readEntries(readTable(if (asExcel) xlsxBytes(rows.toList()) else csvBytes(rows.toList())))

    // ---- round trips ----

    @Test
    fun `a csv this app wrote reads back as the entry it was`() {
        val read = entries(row(description = "chapter 3", tags = "book, quiet"))
        val entry = read.entries.single()
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 0), entry.begin)
        assertEquals(LocalDateTime.of(2026, 8, 3, 10, 30), entry.end)
        assertEquals("reading", entry.activity)
        assertEquals("chapter 3", entry.description)
        assertEquals(listOf("book", "quiet"), entry.tags)
        assertEquals(0, read.ignored)
    }

    @Test
    fun `an xlsx this app wrote reads back the same way`() {
        val entry = entries(row(description = "chapter 3"), asExcel = true).entries.single()
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 0), entry.begin)
        assertEquals("reading", entry.activity)
        assertEquals("chapter 3", entry.description)
    }

    @Test
    fun `a quoted comma stays inside its field`() {
        val entry = entries(row(description = "read \"Dune\", slowly")).entries.single()
        assertEquals("read \"Dune\", slowly", entry.description)
    }

    @Test
    fun `a semicolon-separated csv is read on its own terms`() {
        val text = "Date;Begin;End;Activity\n2026-08-03;09:00;10:30;reading\n"
        val entry = readEntries(readTable(text.toByteArray())).entries.single()
        assertEquals("reading", entry.activity)
        assertEquals(LocalTime.of(10, 30), entry.end.toLocalTime())
    }

    @Test
    fun `a file that isn't a timesheet is turned away`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            readEntries(readTable("name,email\nfizaan,x@y.z\n".toByteArray()))
        }
        assertTrue(e.message!!, e.message!!.contains("Date and Activity"))
    }

    // ---- rows that can't be imported ----

    @Test
    fun `a row still running is counted and left out`() {
        val read = entries(row(), row(end = ""))
        assertEquals(1, read.entries.size)
        assertEquals(1, read.ignored)
    }

    @Test
    fun `a row with no date is counted and left out`() {
        val read = entries(row(date = ""))
        assertTrue(read.entries.isEmpty())
        assertEquals(1, read.ignored)
    }

    @Test
    fun `an entry that ends before it began ran past midnight`() {
        val entry = entries(row(begin = "23:30", end = "00:15")).entries.single()
        assertEquals(LocalDateTime.of(2026, 8, 4, 0, 15), entry.end)
    }

    // ---- what a spreadsheet does to the file ----

    @Test
    fun `a date written as a serial number is still a date`() {
        assertEquals(LocalDate.of(2026, 8, 3), parseDate("46237"))
        assertEquals(LocalTime.of(9, 0), parseTime("0.375"))
        assertNull(parseDate(""))
        assertNull(parseTime("half nine"))
    }

    @Test
    fun `an xlsx with a shared string table reads like any other`() {
        val entry = readEntries(readTable(sharedStringWorkbook())).entries.single()
        assertEquals("reading", entry.activity)
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 0), entry.begin)
    }

    // ---- planning ----

    private fun existing(day: Int, hour: Int, activity: Int) = TimesheetEntry(
        id = day * 100 + hour,
        begin = formatKimai(LocalDateTime.of(2026, 8, day, hour, 0)),
        end = formatKimai(LocalDateTime.of(2026, 8, day, hour + 1, 0)),
        duration = 3600,
        activity = activity,
        project = 1,
    )

    @Test
    fun `an entry the server already holds is counted, not sent again`() {
        val read = entries(row(), row(date = "2026-08-04", activity = "Writing"))
        val plan = planImport(read.entries, listOf(existing(3, 9, 4)), activities)
        assertEquals(1, plan.duplicates)
        assertEquals(1, plan.entries.size)
        assertEquals("Writing", plan.entries.single().activity)
    }

    @Test
    fun `the same row twice in one file is imported once`() {
        val read = entries(row(), row())
        val plan = planImport(read.entries, emptyList(), activities)
        assertEquals(1, plan.entries.size)
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `an activity that exists under another case is not created again`() {
        val read = entries(row(activity = "WRITING"), row(begin = "12:00", activity = "Cooking"))
        val plan = planImport(read.entries, emptyList(), activities)
        assertEquals(listOf("Cooking"), plan.newActivities)
    }

    @Test
    fun `a new activity is asked for once however many entries use it`() {
        val read = entries(
            row(activity = "cooking"),
            row(begin = "12:00", end = "12:30", activity = "cooking"),
        )
        val plan = planImport(read.entries, emptyList(), activities)
        assertEquals(listOf("cooking"), plan.newActivities)
        assertEquals(2, plan.entries.size)
    }

    @Test
    fun `the same activity at a different minute is a different entry`() {
        val read = entries(row(begin = "09:01"))
        val plan = planImport(read.entries, listOf(existing(3, 9, 4)), activities)
        assertEquals(0, plan.duplicates)
        assertEquals(1, plan.entries.size)
    }

    /**
     * A minimal workbook in the shape Excel and LibreOffice write: every string
     * lives in `sharedStrings.xml` and the cells point at it by index.
     */
    private fun sharedStringWorkbook(): ByteArray {
        val strings = listOf("Date", "Begin", "End", "Activity", "2026-08-03", "09:00", "10:30", "reading")
        val shared = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"8\" uniqueCount=\"8\">")
            strings.forEach { append("<si><t>$it</t></si>") }
            append("</sst>")
        }
        fun cell(ref: String, index: Int) = "<c r=\"$ref\" t=\"s\"><v>$index</v></c>"
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            append("<row r=\"1\">")
            listOf("A1", "B1", "C1", "D1").forEachIndexed { i, ref -> append(cell(ref, i)) }
            append("</row><row r=\"2\">")
            listOf("A2", "B2", "C2", "D2").forEachIndexed { i, ref -> append(cell(ref, i + 4)) }
            append("</row></sheetData></worksheet>")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(shared.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
