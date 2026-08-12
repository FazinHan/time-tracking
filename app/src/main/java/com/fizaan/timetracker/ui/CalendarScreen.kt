package com.fizaan.timetracker.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.CalendarState
import com.fizaan.timetracker.data.Activity
import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.util.parseKimaiLocal
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The hour the grid is scrolled to when today isn't one of the days shown. */
private const val DefaultTopHour = 7

/** How far below the top of the view the now-line is parked when opening. */
private const val NowLeadHours = 1.5f

/** The right-pointing marker at the left end of the now-line. */
private val NowArrowWidth = 7.dp
private val NowArrowHeight = 11.dp

/** One entry positioned within a single day, in minutes from midnight. */
private data class DayBlock(
    val entry: TimesheetEntry,
    val startMin: Int,
    val endMin: Int,
)

@Composable
fun CalendarScreen(
    state: CalendarState,
    onMenu: () -> Unit,
    onRefresh: () -> Unit,
    onShift: (days: Int) -> Unit,
    onToday: () -> Unit,
    onClearError: () -> Unit,
) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val visibleDays = if (landscape) 7 else 3

    // Left→right, oldest→newest, ending on the anchor day.
    val days = remember(state.anchor, visibleDays) {
        (visibleDays - 1 downTo 0).map { state.anchor.minusDays(it.toLong()) }
    }
    val headerFmt = remember { DateTimeFormatter.ofPattern("EEE") }
    val rangeFmt = remember { DateTimeFormatter.ofPattern("d MMM") }
    val today = LocalDate.now()
    val axisW = 48.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, "Menu", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = "Calendar",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToday) {
                Icon(Icons.Filled.Today, "Today", tint = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        CacheBanner(state.cached)

        // Range + paging controls.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onShift(-visibleDays) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "${days.first().format(rangeFmt)} – ${days.last().format(rangeFmt)}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onShift(visibleDays) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // Day headers (aligned with the columns below via the shared axis width).
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
            Spacer(Modifier.width(axisW))
            days.forEach { d ->
                val isToday = d == today
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = d.format(headerFmt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .then(
                                if (isToday) Modifier
                                    .width(26.dp).height(26.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                else Modifier,
                            ),
                    ) {
                        Text(
                            text = d.dayOfMonth.toString(),
                            fontSize = 14.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) Color.White
                            else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            CalendarGrid(days, state.entries, state.activities, axisW)
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
                        .clickable { onClearError() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(err, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    days: List<LocalDate>,
    entries: List<TimesheetEntry>,
    activities: List<Activity>,
    axisW: Dp,
) {
    val hourHeight = 56.dp
    val dayHeight = hourHeight * 24
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // Position entries per day, clamped to that calendar day.
    val blocksByDay = remember(days, entries) {
        days.associateWith { day -> blocksForDay(entries, day) }
    }

    // The clock hand. Re-read every half minute so the line creeps down the day
    // instead of freezing wherever the screen happened to open.
    val today = LocalDate.now()
    var nowMin by remember { mutableIntStateOf(minutesOfDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMin = minutesOfDay()
        }
    }

    // Open where the day actually is: the now-line a little below the top edge,
    // so the last hour or two is visible above it. With today off screen there
    // is no line to aim at, and the waking hour is the next best thing.
    val startAtToday = days.contains(today)
    LaunchedEffect(Unit) {
        val hours =
            if (startAtToday) nowMin / 60f - NowLeadHours else DefaultTopHour.toFloat()
        scroll.scrollTo(
            with(density) { (hourHeight * hours.coerceAtLeast(0f)).toPx() }.toInt(),
        )
    }

    val gridColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    Row(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
        // Hour axis.
        Column(modifier = Modifier.width(axisW).height(dayHeight)) {
            for (h in 0..23) {
                Box(modifier = Modifier.fillMaxWidth().height(hourHeight)) {
                    if (h > 0) {
                        Text(
                            text = "%02d:00".format(h),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(y = (-6).dp)
                                .padding(end = 6.dp),
                        )
                    }
                }
            }
        }
        days.forEach { day ->
            DayLane(
                blocks = blocksByDay[day].orEmpty(),
                activities = activities,
                hourHeight = hourHeight,
                dayHeight = dayHeight,
                gridColor = gridColor,
                nowMin = nowMin.takeIf { day == today },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayLane(
    blocks: List<DayBlock>,
    activities: List<Activity>,
    hourHeight: Dp,
    dayHeight: Dp,
    gridColor: Color,
    nowMin: Int?,
    modifier: Modifier = Modifier,
) {
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    BoxWithConstraints(
        modifier = modifier
            .height(dayHeight)
            .drawBehind {
                val hourPx = hourHeight.toPx()
                var y = 0f
                for (i in 0..24) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += hourPx
                }
                drawLine(gridColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
            },
    ) {
        blocks.forEach { b ->
            val topFrac = b.startMin / 1440f
            val heightFrac = ((b.endMin - b.startMin).coerceAtLeast(1)) / 1440f
            val top = dayHeight * topFrac
            val h = dayHeight * heightFrac
            val act = activities.firstOrNull { it.id == b.entry.activity }
            val color = colorForActivity(b.entry.activity, activities)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.5.dp)
                    .offset(y = top)
                    .height(h.coerceAtLeast(14.dp))
                    .background(color.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Column {
                    Text(
                        text = act?.name ?: "#${b.entry.activity}",
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (h > 30.dp) {
                        Text(
                            text = "${minToLabel(b.startMin, timeFmt)}–${minToLabel(b.endMin, timeFmt)}",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // Last, so it crosses the blocks rather than hiding under them.
        if (nowMin != null) NowLine(nowMin)
    }
}

/**
 * Where the day has got to: a red rule across today's column, tipped with an
 * arrow pointing into the day. Red for the same reason a running timer is —
 * it is the one thing on this screen that is happening now, not recorded.
 */
@Composable
private fun NowLine(nowMin: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val y = size.height * (nowMin / 1440f)
        val arrowW = NowArrowWidth.toPx()
        val arrowH = NowArrowHeight.toPx()
        drawLine(
            color = StopRed,
            start = Offset(arrowW, y),
            end = Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
        )
        drawPath(
            path = Path().apply {
                moveTo(0f, y - arrowH / 2f)
                lineTo(arrowW, y)
                lineTo(0f, y + arrowH / 2f)
                close()
            },
            color = StopRed,
        )
    }
}

/** Wall-clock minutes since midnight, right now. */
private fun minutesOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

private fun minToLabel(min: Int, fmt: DateTimeFormatter): String =
    LocalDateTime.of(2000, 1, 1, (min / 60).coerceIn(0, 23), (min % 60).coerceIn(0, 59)).format(fmt)

/** Build the day's blocks, clamping each entry's span to [00:00, 24:00). */
private fun blocksForDay(entries: List<TimesheetEntry>, day: LocalDate): List<DayBlock> {
    val dayStart = day.atStartOfDay()
    val dayEnd = day.plusDays(1).atStartOfDay()
    val out = ArrayList<DayBlock>()
    for (e in entries) {
        val begin = parseKimaiLocal(e.begin) ?: continue
        val end = parseKimaiLocal(e.end) ?: LocalDateTime.now()
        if (!end.isAfter(dayStart) || !begin.isBefore(dayEnd)) continue  // no overlap with this day
        val s = if (begin.isBefore(dayStart)) 0 else begin.hour * 60 + begin.minute
        val eMin = if (end.isAfter(dayEnd)) 1440 else end.hour * 60 + end.minute
        out.add(DayBlock(e, s, eMin.coerceAtLeast(s + 1)))
    }
    return out.sortedBy { it.startMin }
}
