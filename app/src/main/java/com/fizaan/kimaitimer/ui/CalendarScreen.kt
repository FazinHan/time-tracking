package com.fizaan.kimaitimer.ui

import android.content.res.Configuration
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.CalendarState
import com.fizaan.kimaitimer.data.Activity
import com.fizaan.kimaitimer.data.TimesheetEntry
import com.fizaan.kimaitimer.util.parseKimaiLocal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
                                    .background(KimaiGreen, CircleShape)
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
                    color = KimaiGreen,
                )
            }
            state.error?.let { err ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(KimaiRed, CircleShape)
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
    // Auto-scroll to the earliest entry on screen (fallback ~7am).
    val firstMin = remember(blocksByDay) {
        blocksByDay.values.flatten().minOfOrNull { it.startMin } ?: (7 * 60)
    }
    LaunchedEffect(firstMin) {
        val hourPx = with(density) { hourHeight.toPx() }
        val y = (firstMin / 60f - 0.5f).coerceAtLeast(0f) * hourPx
        scroll.scrollTo(y.toInt())
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
    }
}

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
