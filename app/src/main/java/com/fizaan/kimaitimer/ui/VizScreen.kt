package com.fizaan.kimaitimer.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.PieMode
import com.fizaan.kimaitimer.VizPeriod
import com.fizaan.kimaitimer.VizState
import com.fizaan.kimaitimer.VizTab
import com.fizaan.kimaitimer.data.Activity
import com.fizaan.kimaitimer.data.TimesheetEntry
import com.fizaan.kimaitimer.util.entryLocalDate
import com.fizaan.kimaitimer.util.entrySeconds
import com.fizaan.kimaitimer.util.formatDuration
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.min

data class Slice(val label: String, val color: Color, val seconds: Long)

private data class DayStack(val date: LocalDate, val segments: List<Slice>, val total: Long)

@Composable
fun VizScreen(
    state: VizState,
    onMenu: () -> Unit,
    onTab: (VizTab) -> Unit,
    onPieMode: (PieMode) -> Unit,
    onPeriod: (VizPeriod) -> Unit,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
) {
    // Live clock so running entries keep growing while the screen is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, "Menu", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = "Visualisations",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        TabRow(
            selectedTabIndex = if (state.tab == VizTab.PIE) 0 else 1,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Tab(
                selected = state.tab == VizTab.PIE,
                onClick = { onTab(VizTab.PIE) },
                text = { Text("Pie") },
            )
            Tab(
                selected = state.tab == VizTab.BAR,
                onClick = { onTab(VizTab.BAR) },
                text = { Text("Bar") },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (state.tab) {
                VizTab.PIE -> PieTab(state, now, onPieMode, onPeriod)
                VizTab.BAR -> BarTab(state, now)
            }
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = KimaiGreen,
                )
            }
            state.error?.let { err ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(KimaiRed, CircleShape)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(err, color = Color.White, modifier = Modifier)
                }
                LaunchedEffect(err) { delay(5000); onClearError() }
            }
        }
    }
}

// ---------------- Pie ----------------

private fun computeSlices(
    entries: List<TimesheetEntry>,
    activities: List<Activity>,
    mode: PieMode,
    now: Long,
): List<Slice> {
    return when (mode) {
        PieMode.ACTIVITY -> entries
            .groupBy { it.activity }
            .map { (actId, list) ->
                Slice(
                    label = activities.firstOrNull { it.id == actId }?.name ?: "#$actId",
                    color = colorForActivity(actId, activities),
                    seconds = list.sumOf { entrySeconds(it.begin, it.end, it.duration, now) },
                )
            }
        PieMode.TAG -> {
            val allTags = entries.flatMap { it.tags.orEmpty() }.distinct().sorted()
            entries
                .groupBy { it.tags?.firstOrNull()?.takeIf { t -> t.isNotBlank() } ?: "" }
                .map { (tag, list) ->
                    Slice(
                        label = tag.ifBlank { "untagged" },
                        color = colorForTag(tag, allTags),
                        seconds = list.sumOf { entrySeconds(it.begin, it.end, it.duration, now) },
                    )
                }
        }
    }.filter { it.seconds > 0 }.sortedByDescending { it.seconds }
}

@Composable
private fun PieTab(
    state: VizState,
    now: Long,
    onPieMode: (PieMode) -> Unit,
    onPeriod: (VizPeriod) -> Unit,
) {
    val slices = remember(state.pieEntries, state.activities, state.pieMode, now) {
        computeSlices(state.pieEntries, state.activities, state.pieMode, now)
    }
    val total = slices.sumOf { it.seconds }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            if (total == 0L && !state.loading) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "Nothing tracked in this period yet.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            } else {
                // Tag pie: the centre shows a productivity score — productive
                // share of the *classified* (tagged) time — instead of the total.
                // Semi-productive time counts at half weight.
                val productive = slices.firstOrNull { it.label == "productive" }?.seconds ?: 0L
                val unproductive = slices.firstOrNull { it.label == "unproductive" }?.seconds ?: 0L
                val semiProductive = slices.firstOrNull { it.label == "semi-productive" }?.seconds ?: 0L
                val classified = productive + unproductive + semiProductive
                if (state.pieMode == PieMode.TAG && classified > 0) {
                    PieChart(
                        slices, total,
                        centerLabel = "${((productive + semiProductive * 0.5f) * 100f / classified + 0.5f).toInt()}%",
                        centerSub = "productive",
                    )
                } else {
                    PieChart(slices, total, centerLabel = formatDuration(total), centerSub = "total")
                }
                Spacer(Modifier.height(20.dp))
                slices.forEach { s -> LegendRow(s, total) }
            }
            Spacer(Modifier.height(8.dp))
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.pieMode == PieMode.ACTIVITY,
                onClick = { onPieMode(PieMode.ACTIVITY) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Activity pie") }
            SegmentedButton(
                selected = state.pieMode == PieMode.TAG,
                onClick = { onPieMode(PieMode.TAG) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Tag pie") }
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            VizPeriod.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = state.period == p,
                    onClick = { onPeriod(p) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                ) { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PieChart(slices: List<Slice>, total: Long, centerLabel: String, centerSub: String) {
    val diameter = 240.dp
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val strokeW = 46.dp.toPx()
            val gapPx = 2.dp.toPx()
            val inset = strokeW / 2
            val arcSize = Size(size.width - strokeW, size.height - strokeW)
            val radius = arcSize.width / 2
            // A 2dp surface gap between slices, expressed in degrees at this radius.
            val gapDeg = if (slices.size > 1) (gapPx / (2f * Math.PI.toFloat() * radius)) * 360f else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = (s.seconds.toFloat() / total) * 360f
                drawArc(
                    color = s.color,
                    startAngle = start + gapDeg / 2,
                    sweepAngle = (sweep - gapDeg).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeW),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLabel,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = centerSub,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun LegendRow(s: Slice, total: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).background(s.color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            text = s.label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(s.seconds),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${(s.seconds * 100f / total).toInt()}%",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 13.sp,
            modifier = Modifier.width(38.dp),
            textAlign = TextAlign.End,
        )
    }
}

// ---------------- Bar ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarTab(state: VizState, now: Long) {
    val days = remember(state.barEntries, state.activities, now) {
        buildDayStacks(state.barEntries, state.activities, now)
    }
    val maxSeconds = days.maxOfOrNull { it.total } ?: 0L
    val niceMaxH = ceil(maxSeconds / 3600.0).toInt().coerceAtLeast(1)
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val visibleDays = if (landscape) 7 else 3
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE d/M") }

    // Legend: only activities that actually appear in the window.
    val legendActs = remember(state.barEntries, state.activities) {
        state.barEntries.map { it.activity }.distinct()
            .mapNotNull { id -> state.activities.firstOrNull { it.id == id } }
            .sortedBy { it.name.lowercase() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        BoxWithConstraints(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            val axisW = 44.dp
            val slotW = (maxWidth - axisW) / visibleDays
            val labelH = 22.dp
            val chartH = maxHeight - labelH * 2
            Row {
                Column(modifier = Modifier.width(axisW)) {
                    Spacer(Modifier.height(labelH))
                    Column(
                        modifier = Modifier.height(chartH).fillMaxWidth(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End,
                    ) {
                        for (i in 4 downTo 0) {
                            Text(
                                text = axisLabel(niceMaxH * i / 4.0),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
                // Newest day docked at the right edge; scroll left into history.
                LazyRow(modifier = Modifier.weight(1f), reverseLayout = true) {
                    items(days.size) { i ->
                        val day = days[days.size - 1 - i]
                        DayColumn(day, slotW, chartH, labelH, niceMaxH, dateFmt)
                    }
                }
            }
        }
        if (legendActs.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                legendActs.forEach { act ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp)
                                .background(colorForActivity(act.id, state.activities), CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            act.name,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

private fun buildDayStacks(
    entries: List<TimesheetEntry>,
    activities: List<Activity>,
    now: Long,
): List<DayStack> {
    val byDay = entries.groupBy { entryLocalDate(it.begin) }
    val today = LocalDate.now()
    return (29 downTo 0).map { back ->
        val date = today.minusDays(back.toLong())
        val dayEntries = byDay[date].orEmpty()
        val segments = dayEntries
            .groupBy { it.activity }
            .map { (actId, list) ->
                Slice(
                    label = activities.firstOrNull { it.id == actId }?.name ?: "#$actId",
                    color = colorForActivity(actId, activities),
                    seconds = list.sumOf { entrySeconds(it.begin, it.end, it.duration, now) },
                )
            }
            .filter { it.seconds > 0 }
            .sortedBy { it.label.lowercase() }
        DayStack(date, segments, segments.sumOf { it.seconds })
    }
}

@Composable
private fun DayColumn(
    day: DayStack,
    slotW: androidx.compose.ui.unit.Dp,
    chartH: androidx.compose.ui.unit.Dp,
    labelH: androidx.compose.ui.unit.Dp,
    niceMaxH: Int,
    dateFmt: DateTimeFormatter,
) {
    val gridColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    Column(modifier = Modifier.width(slotW), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(labelH), contentAlignment = Alignment.Center) {
            if (day.total > 0) {
                Text(
                    text = formatDuration(day.total),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                )
            }
        }
        Canvas(modifier = Modifier.height(chartH).fillMaxWidth()) {
            val h = size.height
            val w = size.width
            // Hairline gridlines at quarter marks (align across columns).
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            val scaleSeconds = niceMaxH * 3600f
            val barW = w * 0.6f
            val x = (w - barW) / 2f
            val gap = 2.dp.toPx()
            var cursor = h
            day.segments.forEachIndexed { idx, seg ->
                val segH = (seg.seconds / scaleSeconds) * h
                val isTop = idx == day.segments.lastIndex
                val top = cursor - segH
                val drawnH = (segH - if (isTop) 0f else gap).coerceAtLeast(1f)
                if (isTop) {
                    // Rounded data-end on the topmost segment only.
                    val r = min(4.dp.toPx(), drawnH / 2)
                    val path = Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                rect = Rect(x, top, x + barW, top + drawnH),
                                topLeft = androidx.compose.ui.geometry.CornerRadius(r, r),
                                topRight = androidx.compose.ui.geometry.CornerRadius(r, r),
                            )
                        )
                    }
                    drawPath(path, seg.color)
                } else {
                    drawRect(seg.color, Offset(x, top), Size(barW, drawnH))
                }
                cursor = top
            }
        }
        Box(Modifier.height(labelH), contentAlignment = Alignment.Center) {
            Text(
                text = day.date.format(dateFmt),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}

private fun axisLabel(hours: Double): String =
    if (hours == hours.toInt().toDouble()) "${hours.toInt()}h"
    else "%.1fh".format(hours)
