package com.fizaan.timetracker.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ---------------- CSV ----------------

/**
 * RFC 4180 with a byte-order mark in front. The BOM is there for one reason:
 * without it a spreadsheet opening this on Windows guesses the encoding and
 * mangles every accented activity name.
 */
fun csvBytes(rows: List<ExportRow>): ByteArray {
    val sb = StringBuilder("﻿")
    sb.appendCsv(ExportColumns)
    rows.forEach { sb.appendCsv(it.cells()) }
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private fun StringBuilder.appendCsv(cells: List<String>) {
    cells.joinTo(this, ",") { cell ->
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }
    }
    append("\r\n")
}

// ---------------- Excel ----------------

/**
 * A .xlsx written by hand.
 *
 * An xlsx is a zip of XML parts, and the handful of them a spreadsheet needs to
 * open a plain table is small enough to write out directly — which beats
 * dragging a spreadsheet library and its several megabytes into an app that
 * wants one sheet of one table. Text is written inline rather than through a
 * shared-strings table, so there is one part fewer to keep consistent.
 */
fun xlsxBytes(rows: List<ExportRow>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.part("[Content_Types].xml", CONTENT_TYPES)
        zip.part("_rels/.rels", ROOT_RELS)
        zip.part("xl/workbook.xml", WORKBOOK)
        zip.part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        zip.part("xl/styles.xml", STYLES)
        zip.part("xl/worksheets/sheet1.xml", sheetXml(rows))
    }
    return out.toByteArray()
}

private fun ZipOutputStream.part(name: String, body: String) {
    putNextEntry(ZipEntry(name))
    write(body.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun sheetXml(rows: List<ExportRow>): String {
    val sb = StringBuilder()
    sb.append(XML_HEAD)
    sb.append(
        "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetData>"
    )
    // The header is bold (style 1); everything below it is plain.
    sb.appendRow(1, ExportColumns.map { it to false }, style = 1)
    rows.forEachIndexed { i, row ->
        // Only the hours column is a number — the rest, dates and clock times
        // included, stay text so nothing gets re-interpreted on the way in.
        val cells = row.cells().mapIndexed { col, value -> value to (col == 4) }
        sb.appendRow(i + 2, cells)
    }
    sb.append("</sheetData></worksheet>")
    return sb.toString()
}

/** One `<row>`; each cell is its value and whether to write it as a number. */
private fun StringBuilder.appendRow(
    index: Int,
    cells: List<Pair<String, Boolean>>,
    style: Int = 0,
) {
    append("<row r=\"").append(index).append("\">")
    cells.forEachIndexed { col, (value, numeric) ->
        val ref = columnName(col) + index
        append("<c r=\"").append(ref).append("\" s=\"").append(style).append('"')
        if (numeric) {
            append("><v>").append(value).append("</v></c>")
        } else {
            append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
            append(value.escapeXml())
            append("</t></is></c>")
        }
    }
    append("</row>")
}

/** 0 -> A, 25 -> Z, 26 -> AA. */
private fun columnName(index: Int): String {
    var n = index
    val sb = StringBuilder()
    while (true) {
        sb.insert(0, 'A' + n % 26)
        n = n / 26 - 1
        if (n < 0) break
    }
    return sb.toString()
}

private fun String.escapeXml(): String = buildString(length) {
    this@escapeXml.forEach { c ->
        when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            // Control characters are not representable in XML 1.0 at all.
            c.code < 0x20 && c != '\t' && c != '\n' -> append(' ')
            else -> append(c)
        }
    }
}

private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

private val CONTENT_TYPES = XML_HEAD +
    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
    "</Types>"

private val ROOT_RELS = XML_HEAD +
    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
    "</Relationships>"

private val WORKBOOK = XML_HEAD +
    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
    "<sheets><sheet name=\"Timesheet\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

private val WORKBOOK_RELS = XML_HEAD +
    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
    "</Relationships>"

// Two fonts and two fills: the plain one, and the bold one the header uses.
// Excel insists on the "gray125" fill being present whether or not it is used.
private val STYLES = XML_HEAD +
    "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
    "<fonts count=\"2\">" +
    "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
    "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
    "</fonts>" +
    "<fills count=\"2\">" +
    "<fill><patternFill patternType=\"none\"/></fill>" +
    "<fill><patternFill patternType=\"gray125\"/></fill>" +
    "</fills>" +
    "<borders count=\"1\"><border/></borders>" +
    "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
    "<cellXfs count=\"2\">" +
    "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
    "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
    "</cellXfs>" +
    "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
    "</styleSheet>"
