package com.fizaan.timetracker.chart

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The one place either chart is actually drawn.
 *
 * It draws onto a plain [Canvas], which is what the screen, a bitmap and a PDF
 * page all are underneath — so an exported chart is the same drawing code as the
 * one on screen rather than a screenshot of it. Everything it needs to decide
 * has been decided already in [ChartLayout]; what is left here is paint.
 *
 * Two rules keep that promise honest across the three surfaces: nothing reads
 * the canvas's own width or height (on screen that is the whole window, not this
 * chart), and nothing uses an effect a PDF page cannot carry — no shadow layers,
 * no blur, no sweep gradients.
 */
object ChartRenderer {

    fun drawTrend(
        canvas: Canvas,
        spec: ChartSpec,
        chart: TrendChart,
        seriesArgb: Int,
        theme: ChartTheme,
        progress: Float = 1f,
    ) {
        val depth = canvas.save()
        try {
            paintBackground(canvas, spec, theme)
            val label = textPaint(9f * spec.u, theme.label)
            val fm = label.fontMetrics
            val labelHeight = fm.descent - fm.ascent
            val yLabelWidth = chart.yLabels.maxOfOrNull { label.measureText(it) } ?: 0f
            val m = metricsFor(spec, yLabelWidth, labelHeight, hasTitle = true)

            drawHeading(canvas, spec, m, theme, chart.title, chart.subtitle)

            // Gridlines first, so the series is drawn over them.
            val grid = strokePaint(m.gridStroke, theme.grid)
            val axis = strokePaint(m.gridStroke, theme.axis)
            chart.yTicks.forEachIndexed { i, tick ->
                val y = yOf(tick, chart.yMax, m.top, m.bottom)
                canvas.drawLine(m.left, y, m.right, y, if (i == 0) axis else grid)
                label.textAlign = Paint.Align.RIGHT
                canvas.drawText(chart.yLabels[i], m.left - 4f * spec.u, y - fm.ascent / 2f - 1f, label)
            }

            drawXLabels(canvas, spec, m, chart.xLabels, label, fm.descent - fm.ascent)
            drawSeries(canvas, m, chart, theme.series(seriesArgb), progress)
        } finally {
            canvas.restoreToCount(depth)
        }
    }

    fun drawMonth(
        canvas: Canvas,
        spec: ChartSpec,
        chart: MonthChart,
        seriesArgb: Int,
        theme: ChartTheme,
    ) {
        val depth = canvas.save()
        try {
            paintBackground(canvas, spec, theme)
            val label = textPaint(9f * spec.u, theme.label)
            val fm = label.fontMetrics
            val labelHeight = fm.descent - fm.ascent
            val m = metricsFor(spec, 0f, labelHeight * 2.4f, hasTitle = true)
            drawHeading(canvas, spec, m, theme, chart.title, chart.subtitle)

            val header = labelHeight * 1.6f
            val geo = gridGeometry(spec, m, MaxWeekRows, header)
            val color = theme.series(seriesArgb)

            // Weekday initials, centred over their columns.
            label.textAlign = Paint.Align.CENTER
            chart.weekdayLabels.forEachIndexed { col, text ->
                canvas.drawText(
                    text,
                    geo.originX + geo.cell * (col + 0.5f),
                    geo.originY - header * 0.35f,
                    label,
                )
            }

            val pad = geo.cell * 0.06f
            val radius = geo.cell * 0.16f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val outline = strokePaint(spec.u * 0.9f, theme.emphasis)
            val dayText = textPaint(geo.cell * 0.30f, theme.label).apply {
                textAlign = Paint.Align.CENTER
            }
            val dayFm = dayText.fontMetrics

            chart.cells.forEachIndexed { index, cell ->
                val (row, col) = cellPosition(chart, index)
                val rect = RectF(
                    geo.originX + geo.cell * col + pad,
                    geo.originY + geo.cell * row + pad,
                    geo.originX + geo.cell * (col + 1) - pad,
                    geo.originY + geo.cell * (row + 1) - pad,
                )
                fill.color = if (cell.sessions == 0) theme.empty
                else withAlpha(color, 0.15f + 0.85f * cell.intensity)
                canvas.drawRoundRect(rect, radius, radius, fill)
                if (index == chart.todayIndex) {
                    canvas.drawRoundRect(rect, radius, radius, outline)
                }
                // Day numbers stay in the ink colour: reading them off a colour
                // that changes with every cell is what makes heat grids illegible.
                dayText.color = if (cell.sessions == 0) theme.subtitle else theme.label
                canvas.drawText(
                    cell.date.dayOfMonth.toString(),
                    rect.centerX(),
                    rect.centerY() - (dayFm.ascent + dayFm.descent) / 2f,
                    dayText,
                )
            }

            drawHeatLegend(canvas, spec, m, chart, color, theme)
        } finally {
            canvas.restoreToCount(depth)
        }
    }

    // ---- pieces ----

    private fun paintBackground(canvas: Canvas, spec: ChartSpec, theme: ChartTheme) {
        if (theme.background == 0) return
        // drawRect, not drawColor: on the screen's canvas drawColor would fill
        // the whole layer rather than this chart's box.
        canvas.drawRect(
            0f, 0f, spec.width, spec.height,
            Paint().apply { color = theme.background },
        )
    }

    private fun drawHeading(
        canvas: Canvas,
        spec: ChartSpec,
        m: ChartMetrics,
        theme: ChartTheme,
        title: String,
        subtitle: String,
    ) {
        val titlePaint = textPaint(m.titleText, theme.title).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        val subPaint = textPaint(m.subtitleText, theme.subtitle)
        val width = spec.width - m.left * 0.2f - 8f * spec.u
        canvas.drawText(ellipsise(title, width, titlePaint), 4f * spec.u, m.titleText, titlePaint)
        canvas.drawText(
            ellipsise(subtitle, width, subPaint),
            4f * spec.u,
            m.titleText + m.subtitleText * 1.5f,
            subPaint,
        )
    }

    private fun drawXLabels(
        canvas: Canvas,
        spec: ChartSpec,
        m: ChartMetrics,
        labels: List<String>,
        paint: Paint,
        labelHeight: Float,
    ) {
        if (labels.isEmpty()) return
        val slot = m.plotWidth / (labels.size - 1).coerceAtLeast(1)
        val picked = pickLabelIndices(labels, slot, 6f * spec.u) { paint.measureText(it) }
        paint.textAlign = Paint.Align.CENTER
        val baseline = m.bottom + labelHeight
        picked.forEach { i ->
            canvas.drawText(labels[i], xOf(i, labels.size, m.left, m.right), baseline, paint)
        }
    }

    private fun drawSeries(
        canvas: Canvas,
        m: ChartMetrics,
        chart: TrendChart,
        argb: Int,
        progress: Float,
    ) {
        val points = chart.points
        if (points.isEmpty()) return
        val reveal = revealOf(points.size, progress)
        if (reveal.drawn == 0 && reveal.tail <= 0f) return

        val line = strokePaint(m.lineStroke, argb).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = Paint.Style.FILL
        }

        fun px(i: Int) = xOf(i, points.size, m.left, m.right)
        fun py(i: Int) = yOf(points[i].value, chart.yMax, m.top, m.bottom)

        val path = Path()
        val last = (reveal.drawn - 1).coerceAtLeast(0)
        path.moveTo(px(0), py(0))
        for (i in 1..last) path.lineTo(px(i), py(i))
        // The leading segment is drawn part-way, so the line grows smoothly
        // instead of jumping a whole day at a time.
        if (reveal.tail > 0f && reveal.drawn in 1 until points.size) {
            val i = reveal.drawn
            path.lineTo(
                px(i - 1) + (px(i) - px(i - 1)) * reveal.tail,
                py(i - 1) + (py(i) - py(i - 1)) * reveal.tail,
            )
        }
        canvas.drawPath(path, line)
        for (i in 0..last) canvas.drawCircle(px(i), py(i), m.dot, dot)
    }

    private fun drawHeatLegend(
        canvas: Canvas,
        spec: ChartSpec,
        m: ChartMetrics,
        chart: MonthChart,
        argb: Int,
        theme: ChartTheme,
    ) {
        val text = textPaint(8f * spec.u, theme.subtitle)
        val box = 7f * spec.u
        val gap = 2f * spec.u
        val steps = 4
        val width = text.measureText("less") + text.measureText("more") +
            steps * (box + gap) + gap * 4
        var x = (spec.width - width) / 2f
        val y = spec.height - box
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("less", x, y + box * 0.85f, text)
        x += text.measureText("less") + gap * 2
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(steps) { i ->
            fill.color = withAlpha(argb, 0.15f + 0.85f * ((i + 1f) / steps))
            canvas.drawRoundRect(
                RectF(x, y, x + box, y + box), box * 0.2f, box * 0.2f, fill,
            )
            x += box + gap
        }
        x += gap
        canvas.drawText("more", x, y + box * 0.85f, text)
        if (chart.busiest > 0) {
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                "busiest day: ${chart.busiest}",
                spec.width - 4f * spec.u,
                y + box * 0.85f,
                text,
            )
        }
    }

    private fun textPaint(size: Float, argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = argb
    }

    private fun strokePaint(width: Float, argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = argb
    }

    /** An activity can be called anything; a title still has to fit its page. */
    private fun ellipsise(value: String, width: Float, paint: Paint): String {
        if (paint.measureText(value) <= width) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > width) end--
        return value.substring(0, end) + "…"
    }
}
