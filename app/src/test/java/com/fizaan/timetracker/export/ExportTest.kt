package com.fizaan.timetracker.export

import com.fizaan.timetracker.data.Activity
import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.epochMillis
import com.fizaan.timetracker.util.formatKimai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

/**
 * The export writers, which are hand-rolled and therefore worth pinning down:
 * a spreadsheet nobody can open is indistinguishable from no export at all.
 */
class ExportTest {

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        formatKimai(LocalDateTime.of(2026, 8, day, hour, minute))

    private val activities = listOf(Activity(id = 4, name = "reading"))

    private fun entry(
        id: Int = 1,
        beginDay: Int = 3,
        beginHour: Int = 9,
        endHour: Int? = 10,
        description: String? = null,
        tags: List<String>? = null,
    ) = TimesheetEntry(
        id = id,
        begin = at(beginDay, beginHour),
        end = endHour?.let { at(beginDay, it, 30) },
        duration = null,
        description = description,
        tags = tags,
        activity = 4,
        project = 1,
    )

    private fun rows(vararg entries: TimesheetEntry) = exportRows(
        entries = entries.toList(),
        activities = activities,
        customer = "fizaan",
        project = "real life",
        nowMillis = epochMillis(LocalDateTime.of(2026, 8, 3, 12, 0)),
    )

    // ---- rows ----

    @Test
    fun `a closed entry keeps its clock times and duration`() {
        val row = rows(entry()).single()
        assertEquals("2026-08-03", row.date)
        assertEquals("09:00", row.begin)
        assertEquals("10:30", row.end)
        assertEquals("1:30", row.duration)
        assertEquals(1.5, row.hours, 0.001)
        assertEquals("reading", row.activity)
        assertEquals("real life", row.project)
    }

    @Test
    fun `a running entry has no end and counts up to now`() {
        val row = rows(entry(endHour = null)).single()
        assertEquals("", row.end)
        assertEquals("3:00", row.duration)
        assertEquals(3.0, row.hours, 0.001)
    }

    @Test
    fun `an unknown activity falls back to its id`() {
        val row = exportRows(
            entries = listOf(entry()),
            activities = emptyList(),
            customer = "c",
            project = "p",
        ).single()
        assertEquals("#4", row.activity)
    }

    @Test
    fun `rows come out oldest first`() {
        val out = rows(entry(id = 2, beginDay = 5), entry(id = 1, beginDay = 3))
        assertEquals(listOf("2026-08-03", "2026-08-05"), out.map { it.date })
    }

    // ---- CSV ----

    @Test
    fun `csv starts with a byte-order mark and the header`() {
        val text = String(csvBytes(emptyList()), Charsets.UTF_8)
        assertEquals('﻿', text.first())
        assertEquals(
            "Date,Begin,End,Duration,Hours,Customer,Project,Activity,Description,Tags",
            text.trimStart('﻿').lineSequence().first(),
        )
    }

    @Test
    fun `csv quotes commas and doubles quotes`() {
        val text = String(
            csvBytes(rows(entry(description = "read \"Dune\", slowly"))),
            Charsets.UTF_8,
        )
        assertTrue(text, text.contains("\"read \"\"Dune\"\", slowly\""))
    }

    @Test
    fun `csv leaves a plain field alone`() {
        val text = String(csvBytes(rows(entry(description = "plain"))), Charsets.UTF_8)
        assertTrue(text, text.contains(",plain,"))
    }

    // ---- Excel ----

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun `xlsx carries every part a reader looks for`() {
        val parts = unzip(xlsxBytes(rows(entry())))
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
            ),
            parts.keys.toList(),
        )
    }

    @Test
    fun `xlsx writes the header inline and the hours as a number`() {
        val sheet = unzip(xlsxBytes(rows(entry())))["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet, sheet.contains("<c r=\"A1\" s=\"1\" t=\"inlineStr\">"))
        assertTrue(sheet, sheet.contains("<t xml:space=\"preserve\">Date</t>"))
        // Hours is column E, and the only cell written without a type.
        assertTrue(sheet, sheet.contains("<c r=\"E2\" s=\"0\"><v>1.5</v></c>"))
    }

    @Test
    fun `xlsx escapes markup in a description`() {
        val sheet = unzip(xlsxBytes(rows(entry(description = "a < b & \"c\""))))
            .getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet, sheet.contains("a &lt; b &amp; &quot;c&quot;"))
        assertTrue(sheet, !sheet.contains("a < b"))
    }

    @Test
    fun `xlsx has one row per entry plus the header`() {
        val sheet = unzip(xlsxBytes(rows(entry(id = 1), entry(id = 2, beginDay = 4))))
            .getValue("xl/worksheets/sheet1.xml")
        assertEquals(3, Regex("<row ").findAll(sheet).count())
        assertTrue(sheet, sheet.contains("<row r=\"3\">"))
    }
}
