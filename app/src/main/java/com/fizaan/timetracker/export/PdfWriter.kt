package com.fizaan.timetracker.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

// A4 in PostScript points, which is what PdfDocument measures pages in.
private const val PageWidth = 595
private const val PageHeight = 842
private const val Margin = 36f
private const val RowHeight = 14f

/** Column widths, in points, summing to the printable width. */
private val PdfColumns = listOf(
    "Date" to 60f,
    "Begin" to 34f,
    "End" to 34f,
    "Duration" to 46f,
    "Activity" to 104f,
    "Tags" to 85f,
    "Description" to 160f,
)

/** The same seven fields, in the order [PdfColumns] names them. */
private fun ExportRow.pdfCells(): List<String> =
    listOf(date, begin, end, duration, activity, tags, description)

/**
 * Render the rows as a paginated A4 table and write it to [out].
 *
 * [title] and [subtitle] head the first page; every page carries the column
 * headings and its own number, so a printout stays readable once it is a stack
 * of loose paper.
 */
fun writePdf(
    rows: List<ExportRow>,
    title: String,
    subtitle: String,
    totalLine: String,
    out: OutputStream,
) {
    val doc = PdfDocument()
    val text = Paint().apply { textSize = 8f; isAntiAlias = true }
    val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    val rule = Paint().apply { strokeWidth = 0.5f; color = 0xFF999999.toInt() }
    val heading = Paint(bold).apply { textSize = 14f }
    val note = Paint(text).apply { color = 0xFF666666.toInt() }

    var page = 0
    var current: PdfDocument.Page? = null
    var y = 0f

    fun startPage(): PdfDocument.Page {
        page++
        val p = doc.startPage(
            PdfDocument.PageInfo.Builder(PageWidth, PageHeight, page).create(),
        )
        y = Margin
        if (page == 1) {
            p.canvas.drawText(title, Margin, y + 12f, heading)
            y += 24f
            p.canvas.drawText(subtitle, Margin, y, note)
            y += 18f
        }
        // Column headings, ruled off from the rows beneath them.
        var x = Margin
        PdfColumns.forEach { (label, width) ->
            p.canvas.drawText(label, x, y, bold)
            x += width
        }
        y += 4f
        p.canvas.drawLine(Margin, y, PageWidth - Margin, y, rule)
        y += RowHeight
        return p
    }

    current = startPage()
    rows.forEach { row ->
        if (y > PageHeight - Margin - RowHeight * 2) {
            current?.let { finishPage(doc, it, page, text) }
            current = startPage()
        }
        val canvas = current!!.canvas
        var x = Margin
        row.pdfCells().forEachIndexed { i, value ->
            val width = PdfColumns[i].second
            canvas.drawText(clip(value, width - 4f, text), x, y, text)
            x += width
        }
        y += RowHeight
    }

    current?.let { p ->
        y += 4f
        p.canvas.drawLine(Margin, y, PageWidth - Margin, y, rule)
        y += RowHeight
        p.canvas.drawText(totalLine, Margin, y, bold)
        finishPage(doc, p, page, text)
    }

    doc.writeTo(out)
    doc.close()
}

private fun finishPage(doc: PdfDocument, p: PdfDocument.Page, number: Int, paint: Paint) {
    p.canvas.drawText(
        "Page $number",
        PageWidth - Margin - paint.measureText("Page $number"),
        PageHeight - Margin / 2f,
        paint,
    )
    doc.finishPage(p)
}

/**
 * Cut [value] to [width] points, ending in an ellipsis when it doesn't fit.
 *
 * Line breaks are flattened first: a cell is one line of a table, and a
 * multi-line description would otherwise be drawn with its lines run together
 * into one word.
 */
private fun clip(value: String, width: Float, paint: Paint): String {
    val flat = value.replace(Regex("\\s*\\R+\\s*"), " · ").trim()
    if (paint.measureText(flat) <= width) return flat
    var end = flat.length
    while (end > 1 && paint.measureText(flat.substring(0, end) + "…") > width) end--
    return flat.substring(0, end) + "…"
}
