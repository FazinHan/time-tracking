package com.fizaan.timetracker.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.PieMode
import com.fizaan.timetracker.pieRange
import com.fizaan.timetracker.VizPeriod
import com.fizaan.timetracker.VizState
import com.fizaan.timetracker.VizTab
import com.fizaan.timetracker.data.Activity
import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.LIFE_THINGS
import com.fizaan.timetracker.util.TaggedSpan
import com.fizaan.timetracker.util.entryLocalDate
import com.fizaan.timetracker.util.entrySeconds
import com.fizaan.timetracker.util.formatDuration
import com.fizaan.timetracker.util.parseKimaiMillis
import com.fizaan.timetracker.util.resolveTagMillis
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.min

/**
 * One drawable share of a chart. [activityId]/[tag] identify what was
 * aggregated so clicks can open the timesheet pre-filtered
 * (tag == UNTAGGED for the untagged bucket). [activityIds] is set instead of
 * [activityId] on the collapsed "Other" slice, which stands for several.
 */
data class Slice(
    val label: String,
    val color: Color,
    val seconds: Long,
    val activityId: Int? = null,
    val tag: String? = null,
    val activityIds: List<Int>? = null,
)

private data class DayStack(val date: LocalDate, val segments: List<Slice>, val total: Long)

@Composable
fun VizScreen(
    state: VizState,
    onMenu: () -> Unit,
    onTab: (VizTab) -> Unit,
    onPieMode: (PieMode) -> Unit,
    onPeriod: (VizPeriod) -> Unit,
    onShiftPie: (Int) -> Unit,
    onPieToday: () -> Unit,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
    onLegendClick: (activityId: Int?, tag: String?, from: LocalDate, to: LocalDate, activityIds: List<Int>?) -> Unit,
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
            if (state.tab == VizTab.PIE && state.pieOffset != 0) {
                IconButton(onClick = onPieToday) {
                    Icon(Icons.Filled.Today, "Present period", tint = MaterialTheme.colorScheme.onBackground)
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        CacheBanner(state.cached)

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
                VizTab.PIE -> PieTab(state, now, onPieMode, onPeriod, onShiftPie, onLegendClick)
                VizTab.BAR -> BarTab(state, now, onLegendClick)
            }
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.error?.let { err ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(StopRed, CircleShape)
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
                    activityId = actId,
                )
            }
        // Productivity is a claim about time, not about entries, so parallel
        // timers are resolved onto one timeline first: see resolveTagMillis.
        PieMode.TAG -> {
            val allTags = entries.flatMap { it.tags.orEmpty() }.distinct().sorted()
            val spans = entries.mapNotNull { e ->
                val begin = parseKimaiMillis(e.begin) ?: return@mapNotNull null
                val end = if (e.end == null) now else parseKimaiMillis(e.end) ?: return@mapNotNull null
                TaggedSpan(begin, end, e.tags?.firstOrNull()?.takeIf { t -> t.isNotBlank() } ?: "")
            }
            resolveTagMillis(spans).map { (tag, millis) ->
                Slice(
                    label = tag.ifBlank { "untagged" },
                    color = colorForTag(tag, allTags),
                    seconds = millis / 1000,
                    tag = tag,
                )
            }
        }
    }.filter { it.seconds > 0 }.sortedByDescending { it.seconds }
}

/** How much of the pie the collapsed wedge is allowed to swallow. */
private const val OtherShareCap = 0.05f

/** Below this many slivers, collapsing them costs more clarity than it buys. */
private const val MinSlicesToCollapse = 3

/**
 * Fold the crowded tail of a pie into one "Other" wedge — a drawing decision
 * only: the legend below still lists every one of them, and the wedge carries
 * their ids so it opens the timesheet on exactly that set.
 *
 * The tail grows from the smallest slice up for as long as the whole of it
 * stays within [OtherShareCap] of the pie, so "Other" is always a sliver
 * itself. Collapsing by each slice's own size instead would let a long tail of
 * small activities add up to a wedge bigger than anything it hides.
 */
private fun condenseSmall(slices: List<Slice>): List<Slice> {
    val total = slices.sumOf { it.seconds }
    if (total <= 0) return slices
    val cap = (total * OtherShareCap).toLong()
    // slices arrive largest first; walk back up from the smallest.
    var tail = 0L
    var count = 0
    for (s in slices.asReversed()) {
        if (tail + s.seconds > cap) break
        tail += s.seconds
        count++
    }
    if (count < MinSlicesToCollapse) return slices
    return slices.dropLast(count) + Slice(
        label = "Other",
        color = OtherGray,
        seconds = tail,
        activityIds = slices.takeLast(count).mapNotNull { it.activityId },
    )
}

private val DayLabelFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val DayMonthFmt = DateTimeFormatter.ofPattern("d MMM")
private val MonthLabelFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

/** What the paged period is called: "Today", "20 – 26 Jul", "June 2026", "2025". */
private fun pieRangeLabel(
    period: VizPeriod,
    offset: Int,
    from: LocalDate,
    to: LocalDate,
): String = when (period) {
    VizPeriod.DAY -> when (offset) {
        0 -> "Today"
        -1 -> "Yesterday"
        else -> from.format(DayLabelFmt)
    }
    VizPeriod.WEEK ->
        if (offset == 0) "This week"
        else "${from.format(DayMonthFmt)} – ${to.format(DayMonthFmt)}"
    VizPeriod.MONTH -> from.format(MonthLabelFmt)
    VizPeriod.YEAR -> from.year.toString()
}

@Composable
private fun PieTab(
    state: VizState,
    now: Long,
    onPieMode: (PieMode) -> Unit,
    onPeriod: (VizPeriod) -> Unit,
    onShiftPie: (Int) -> Unit,
    onLegendClick: (activityId: Int?, tag: String?, from: LocalDate, to: LocalDate, activityIds: List<Int>?) -> Unit,
) {
    val slices = remember(state.pieEntries, state.activities, state.pieMode, now) {
        computeSlices(state.pieEntries, state.activities, state.pieMode, now)
    }
    val total = slices.sumOf { it.seconds }
    // 'life things' stays out of the drawn pie (it plays no part in the
    // productivity score) but keeps its legend row below. A long tail of tiny
    // activities is collapsed into one wedge, for the drawing only.
    val drawnSlices = remember(slices, state.pieMode) {
        if (state.pieMode == PieMode.TAG) slices.filterNot { it.tag == LIFE_THINGS }
        else condenseSmall(slices)
    }
    val drawnTotal = drawnSlices.sumOf { it.seconds }

    val today = LocalDate.now()
    val (from, to) = remember(state.period, state.pieOffset, today) {
        pieRange(state.period, state.pieOffset, today)
    }

    // Tapping a slice pulls it out and names it; tapping it again is the same
    // as tapping its legend row. Any change of pie drops the selection.
    var selected by remember(state.pieMode, state.period, state.pieOffset) {
        mutableStateOf<String?>(null)
    }
    val selectedSlice = drawnSlices.firstOrNull { it.label == selected }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Same paging control the calendar carries: back and forward a whole
        // period at a time, with the present one as the forward stop.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onShiftPie(-1) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = pieRangeLabel(state.period, state.pieOffset, from, to),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onShiftPie(1) }, enabled = state.pieOffset < 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next",
                    tint = MaterialTheme.colorScheme.onBackground
                        .copy(alpha = if (state.pieOffset < 0) 1f else 0.25f),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            if (total == 0L && !state.loading) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "Nothing tracked in this period yet.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            } else {
                val productive = slices.firstOrNull { it.label == "productive" }?.seconds ?: 0L
                val unproductive = slices.firstOrNull { it.label == "unproductive" }?.seconds ?: 0L
                val semiProductive = slices.firstOrNull { it.label == "semi-productive" }?.seconds ?: 0L
                val classified = productive + unproductive + semiProductive
                val onSliceTap: (Slice?) -> Unit = { s ->
                    when {
                        s == null -> selected = null
                        s.label == selected ->
                            onLegendClick(s.activityId, s.tag, from, to, s.activityIds)
                        else -> selected = s.label
                    }
                }
                when {
                    selectedSlice != null -> PieChart(
                        drawnSlices, drawnTotal, selectedSlice.label, onSliceTap,
                        centerLabel = formatDuration(selectedSlice.seconds),
                        centerSub = selectedSlice.label,
                    )
                    // Productivity pie: the centre shows the score rather than a
                    // total — productive share of the classified (tagged) time,
                    // with semi-productive counting at half weight.
                    state.pieMode == PieMode.TAG && classified > 0 -> PieChart(
                        drawnSlices, drawnTotal, null, onSliceTap,
                        centerLabel = "${((productive + semiProductive * 0.5f) * 100f / classified + 0.5f).toInt()}%",
                        centerSub = "productive",
                    )
                    else -> PieChart(
                        drawnSlices, drawnTotal, null, onSliceTap,
                        centerLabel = formatDuration(total), centerSub = "total",
                    )
                }
                Spacer(Modifier.height(20.dp))
                slices.forEach { s ->
                    LegendRow(s, total) { onLegendClick(s.activityId, s.tag, from, to, null) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.pieMode == PieMode.ACTIVITY,
                onClick = { onPieMode(PieMode.ACTIVITY) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Activities") }
            SegmentedButton(
                selected = state.pieMode == PieMode.TAG,
                onClick = { onPieMode(PieMode.TAG) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Productivity") }
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

/**
 * The ring chart. [selectedLabel] is drawn pulled out of the circle; [onTap]
 * receives the slice under the finger, or null for a tap that hit no slice.
 */
@Composable
private fun PieChart(
    slices: List<Slice>,
    total: Long,
    selectedLabel: String?,
    onTap: (Slice?) -> Unit,
    centerLabel: String,
    centerSub: String,
) {
    val diameter = 240.dp
    val explode = 10.dp
    val ring = 46.dp
    Box(contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .pointerInput(slices, total) {
                    // The band is widened by the pull-out distance so a tap on a
                    // slice that has already moved out still lands on it.
                    val outer = size.width / 2f
                    val inner = outer - ring.toPx() - explode.toPx()
                    detectTapGestures { at ->
                        onTap(sliceAt(at, size.width / 2f, size.height / 2f, inner, outer + explode.toPx(), slices, total))
                    }
                }
        ) {
            val strokeW = ring.toPx()
            val gapPx = 2.dp.toPx()
            val inset = strokeW / 2
            val arcSize = Size(size.width - strokeW, size.height - strokeW)
            val radius = arcSize.width / 2
            // A 2dp surface gap between slices, expressed in degrees at this radius.
            val gapDeg = if (slices.size > 1) (gapPx / (2f * Math.PI.toFloat() * radius)) * 360f else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = (s.seconds.toFloat() / total) * 360f
                // The pulled-out slice is the same arc, shifted along its own
                // bisector so it leaves the circle without changing shape.
                val shift = if (s.label == selectedLabel) {
                    val mid = Math.toRadians((start + sweep / 2).toDouble())
                    Offset(
                        (kotlin.math.cos(mid) * explode.toPx()).toFloat(),
                        (kotlin.math.sin(mid) * explode.toPx()).toFloat(),
                    )
                } else Offset.Zero
                drawArc(
                    color = s.color,
                    startAngle = start + gapDeg / 2,
                    sweepAngle = (sweep - gapDeg).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = Offset(inset + shift.x, inset + shift.y),
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

/**
 * Which slice a tap at [at] landed on, by the angle around the centre —
 * null for the hole in the middle or anything outside the ring, both of which
 * read as "never mind".
 */
private fun sliceAt(
    at: Offset,
    cx: Float,
    cy: Float,
    innerRadius: Float,
    outerRadius: Float,
    slices: List<Slice>,
    total: Long,
): Slice? {
    if (total <= 0L) return null
    val dx = at.x - cx
    val dy = at.y - cy
    val dist = kotlin.math.hypot(dx, dy)
    if (dist < innerRadius || dist > outerRadius) return null
    // Slices are laid out clockwise from twelve o'clock; atan2 starts at three.
    val deg = (Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 450.0) % 360.0
    var start = 0.0
    slices.forEach { s ->
        val sweep = s.seconds.toDouble() / total * 360.0
        if (deg >= start && deg < start + sweep) return s
        start += sweep
    }
    return slices.lastOrNull()   // rounding can leave a hair's gap at the end
}

@Composable
private fun LegendRow(s: Slice, total: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
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
private fun BarTab(
    state: VizState,
    now: Long,
    onLegendClick: (activityId: Int?, tag: String?, from: LocalDate, to: LocalDate, activityIds: List<Int>?) -> Unit,
) {
    val days = remember(state.barEntries, state.activities, now) {
        buildDayStacks(state.barEntries, state.activities, now)
    }
    val maxSeconds = days.maxOfOrNull { it.total } ?: 0L
    val niceMaxH = ceil(maxSeconds / 3600.0).toInt().coerceAtLeast(1)
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val visibleDays = if (landscape) 7 else 3
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE d/M") }
    val listState = rememberLazyListState()

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
                LazyRow(state = listState, modifier = Modifier.weight(1f), reverseLayout = true) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            // Filter to the days currently on screen: the list is
                            // reversed, so the first visible item is the newest
                            // visible day, and the window extends back from it.
                            val newest = days.getOrNull(days.size - 1 - listState.firstVisibleItemIndex)
                                ?.date ?: LocalDate.now()
                            onLegendClick(
                                act.id, null,
                                newest.minusDays((visibleDays - 1).toLong()), newest, null,
                            )
                        },
                    ) {
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
