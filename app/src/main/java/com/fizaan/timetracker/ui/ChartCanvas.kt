package com.fizaan.timetracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.chart.ChartFormat
import com.fizaan.timetracker.chart.ChartRenderer
import com.fizaan.timetracker.chart.ChartSpec
import com.fizaan.timetracker.chart.ChartTheme
import com.fizaan.timetracker.chart.MonthChart
import com.fizaan.timetracker.chart.TrendChart
import com.fizaan.timetracker.chart.revealMillis
import com.fizaan.timetracker.data.Activity

/**
 * The charts on screen.
 *
 * Both hand a plain android Canvas to the same renderer the exported file is
 * drawn with, so what is saved is the same picture rather than a screenshot of
 * this one — laid out for the page rather than for the phone, which is the only
 * difference between them.
 */
@Composable
fun TrendCanvas(
    chart: TrendChart,
    seriesArgb: Int,
    modifier: Modifier = Modifier,
) {
    val unit = screenUnit()
    val theme = ChartTheme.screen(MaterialTheme.colorScheme.onBackground.toArgb())

    // Keyed on the chart's signature rather than the point list, which is
    // rebuilt on every recomposition and would restart the reveal each time.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(chart.signature) {
        reveal.snapTo(0f)
        reveal.animateTo(
            1f,
            tween(revealMillis(chart.points.size), easing = LinearEasing),
        )
    }

    Canvas(modifier = modifier.fillMaxWidth().height(240.dp).clipToBounds()) {
        drawIntoCanvas { canvas ->
            // Read inside the draw lambda: the animation then costs a redraw
            // per frame rather than a recomposition.
            ChartRenderer.drawTrend(
                canvas.nativeCanvas,
                ChartSpec(size.width, size.height, unit),
                chart,
                seriesArgb,
                theme,
                reveal.value,
            )
        }
    }
}

@Composable
fun MonthCanvas(
    chart: MonthChart,
    seriesArgb: Int,
    modifier: Modifier = Modifier,
) {
    val unit = screenUnit()
    val theme = ChartTheme.screen(MaterialTheme.colorScheme.onBackground.toArgb())
    Canvas(modifier = modifier.fillMaxWidth().height(340.dp).clipToBounds()) {
        drawIntoCanvas { canvas ->
            ChartRenderer.drawMonth(
                canvas.nativeCanvas,
                ChartSpec(size.width, size.height, unit),
                chart,
                seriesArgb,
                theme,
            )
        }
    }
}

/**
 * One typographic unit on this display. Drawing text through a Paint bypasses
 * Compose, and with it the font scale the user set — so it is folded back in
 * here, within limits a chart can still be laid out at.
 */
@Composable
private fun screenUnit(): Float {
    val density = LocalDensity.current
    return density.density * density.fontScale.coerceIn(0.85f, 1.3f)
}

/**
 * The colour a chart is drawn in: the activity's own, unless the user has
 * picked something else for this chart.
 */
@Composable
fun chartColor(activityId: Int?, activities: List<Activity>, override: String?): Int {
    val picked = parseHexColor(override)
    return (picked ?: colorForActivity(activityId ?: -1, activities)).toArgb()
}

/** Saving a chart as a picture: which format, and what to leave behind it. */
@Composable
fun ChartExportRow(
    exporting: Boolean,
    saved: String?,
    onExport: (ChartFormat, Boolean) -> Unit,
) {
    var transparent by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Save as a picture")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !transparent,
                onClick = { transparent = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("White") }
            SegmentedButton(
                selected = transparent,
                onClick = { transparent = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Transparent") }
        }
        if (transparent) {
            Text(
                "A PDF page has no transparency of its own, so a transparent PDF " +
                    "is saved on white. The PNG keeps its alpha.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onExport(ChartFormat.PNG, transparent) },
                enabled = !exporting,
                modifier = Modifier.weight(1f),
            ) { Text(if (exporting) "Saving…" else "PNG") }
            OutlinedButton(
                onClick = { onExport(ChartFormat.PDF, transparent) },
                enabled = !exporting,
                modifier = Modifier.weight(1f),
            ) { Text(if (exporting) "Saving…" else "PDF") }
        }
        saved?.let { where ->
            Text(
                "Saved to $where",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
