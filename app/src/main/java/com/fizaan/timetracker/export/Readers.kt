package com.fizaan.timetracker.export

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Reading back what [csvBytes] and [xlsxBytes] wrote.
 *
 * A picked file is a grid of text and nothing more; what the columns mean is
 * decided in [readEntries], which is where a file that isn't a timesheet gets
 * turned away. Both readers are deliberately forgiving — the file may have been
 * round-tripped through a spreadsheet, which rewrites dates as numbers, moves
 * strings into a shared table and reorders nothing else.
 */
fun readTable(bytes: ByteArray): List<List<String>> =
    if (looksLikeZip(bytes)) readXlsx(bytes) else readCsv(bytes)

/** Every xlsx is a zip, and no CSV starts "PK". */
private fun looksLikeZip(b: ByteArray): Boolean =
    b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
        (b[2] == 0x03.toByte() || b[2] == 0x05.toByte() || b[2] == 0x07.toByte())

// ---------------- CSV ----------------

/**
 * RFC 4180, plus the two things real files do: a byte-order mark in front, and
 * a separator that isn't always a comma (a spreadsheet saving CSV in a European
 * locale writes semicolons). The separator is taken from the first line.
 */
fun readCsv(bytes: ByteArray): List<List<String>> {
    var text = String(bytes, Charsets.UTF_8)
    if (text.startsWith('\uFEFF')) text = text.substring(1)
    val sep = sniffSeparator(text)

    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                cell.append('"'); i++
            }
            c == '"' -> quoted = !quoted
            !quoted && c == sep -> { row.add(cell.toString()); cell.clear() }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row.add(cell.toString()); cell.clear()
                rows.add(row); row = mutableListOf()
            }
            else -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(row)
    }
    return rows.filterNot { line -> line.all { it.isBlank() } }
}

/** Whichever of the usual separators appears most on the first line. */
private fun sniffSeparator(text: String): Char {
    val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
    return listOf(',', ';', '\t').maxByOrNull { sep -> first.count { it == sep } }
        ?.takeIf { sep -> first.contains(sep) } ?: ','
}

// ---------------- xlsx ----------------

private fun readXlsx(bytes: ByteArray): List<List<String>> {
    val parts = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
            zip.closeEntry()
        }
    }
    val sheet = parts.keys
        .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
        .minOrNull()
        ?.let { parts[it] }
        ?: throw IllegalArgumentException("That spreadsheet has no worksheet in it.")
    val shared = parts["xl/sharedStrings.xml"]?.let(::sharedStrings) ?: emptyList()
    return sheetRows(sheet, shared)
}

private fun parse(bytes: ByteArray, handler: DefaultHandler) {
    SAXParserFactory.newInstance().newSAXParser()
        .parse(ByteArrayInputStream(bytes), handler)
}

/** The workbook's string table: one entry per `<si>`, runs concatenated. */
private fun sharedStrings(bytes: ByteArray): List<String> {
    val out = mutableListOf<String>()
    val text = StringBuilder()
    var inText = false
    parse(bytes, object : DefaultHandler() {
        override fun startElement(uri: String?, local: String?, name: String?, a: Attributes?) {
            when (name) {
                "si" -> text.setLength(0)
                "t" -> inText = true
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) text.appendRange(ch, start, start + length)
        }
        override fun endElement(uri: String?, local: String?, name: String?) {
            when (name) {
                "t" -> inText = false
                "si" -> out.add(text.toString())
            }
        }
    })
    return out
}

private fun sheetRows(bytes: ByteArray, shared: List<String>): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var cells = sortedMapOf<Int, String>()
    var column = 0
    var type: String? = null
    val value = StringBuilder()
    var capture = false

    parse(bytes, object : DefaultHandler() {
        override fun startElement(uri: String?, local: String?, name: String?, a: Attributes?) {
            when (name) {
                "row" -> cells = sortedMapOf()
                "c" -> {
                    column = columnOf(a?.getValue("r"), cells.size)
                    type = a?.getValue("t")
                    value.setLength(0)
                }
                // "v" holds numbers and shared-string indexes; "t" the inline text.
                "v", "t" -> capture = true
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture) value.appendRange(ch, start, start + length)
        }
        override fun endElement(uri: String?, local: String?, name: String?) {
            when (name) {
                "v", "t" -> capture = false
                "c" -> {
                    val raw = value.toString()
                    cells[column] = if (type == "s") {
                        shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }
                    } else {
                        raw
                    }
                }
                // Gaps are real: an empty cell is simply absent from the XML,
                // so the row is rebuilt by index rather than by what's there.
                "row" -> {
                    val width = (cells.keys.lastOrNull() ?: -1) + 1
                    rows.add((0 until width).map { cells[it].orEmpty() })
                }
            }
        }
    })
    return rows.filterNot { line -> line.all { it.isBlank() } }
}

/** "C7" → 2. Falls back to [fallback] when the cell carries no reference. */
private fun columnOf(ref: String?, fallback: Int): Int {
    if (ref.isNullOrBlank()) return fallback
    var n = 0
    for (c in ref) {
        if (!c.isLetter()) break
        n = n * 26 + (c.uppercaseChar() - 'A' + 1)
    }
    return (n - 1).coerceAtLeast(0)
}
