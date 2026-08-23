package com.fizaan.timetracker.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import kotlin.math.roundToInt

/** What a chart can leave the app as. */
enum class ChartFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    PDF("PDF", "pdf", "application/pdf"),
}

/**
 * A chart, written out as a picture.
 *
 * Both formats are the same page: A4 landscape, measured in PostScript points
 * exactly as the timesheet PDF is. The PNG is that page rasterised — the canvas
 * is scaled up rather than the image, so the lines and the lettering are drawn
 * at the final resolution instead of being blown up afterwards, and the two
 * files are the same picture by construction.
 */
object ChartImage {

    /** A4 landscape in points. */
    const val PageWidth = 842f
    const val PageHeight = 595f

    /** Wide enough to drop into a document without looking soft. */
    const val PngWidth = 2000

    fun writeTrend(
        chart: TrendChart,
        seriesArgb: Int,
        format: ChartFormat,
        transparent: Boolean,
        out: OutputStream,
    ) = write(format, transparent, out) { canvas, spec, theme ->
        ChartRenderer.drawTrend(canvas, spec, chart, seriesArgb, theme)
    }

    fun writeMonth(
        chart: MonthChart,
        seriesArgb: Int,
        format: ChartFormat,
        transparent: Boolean,
        out: OutputStream,
    ) = write(format, transparent, out) { canvas, spec, theme ->
        ChartRenderer.drawMonth(canvas, spec, chart, seriesArgb, theme)
    }

    private fun write(
        format: ChartFormat,
        transparent: Boolean,
        out: OutputStream,
        draw: (Canvas, ChartSpec, ChartTheme) -> Unit,
    ) {
        val theme = ChartTheme.forExport(transparent, pdf = format == ChartFormat.PDF)
        // One point per unit, the same scale PdfWriter draws its table at.
        val spec = ChartSpec(PageWidth, PageHeight, u = 1f)
        when (format) {
            ChartFormat.PDF -> {
                val doc = PdfDocument()
                val page = doc.startPage(
                    PdfDocument.PageInfo
                        .Builder(PageWidth.toInt(), PageHeight.toInt(), 1)
                        .create(),
                )
                draw(page.canvas, spec, theme)
                doc.finishPage(page)
                doc.writeTo(out)
                doc.close()
            }
            ChartFormat.PNG -> {
                val scale = PngWidth / PageWidth
                val bitmap = Bitmap.createBitmap(
                    PngWidth,
                    (PageHeight * scale).roundToInt(),
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    val canvas = Canvas(bitmap)
                    canvas.scale(scale, scale)
                    draw(canvas, spec, theme)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
}
