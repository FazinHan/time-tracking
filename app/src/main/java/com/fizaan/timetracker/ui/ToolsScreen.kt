package com.fizaan.timetracker.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.BatchAction
import com.fizaan.timetracker.CacheInfo
import com.fizaan.timetracker.FreqResult
import com.fizaan.timetracker.ToolsState
import com.fizaan.timetracker.data.CACHE_MAX_BYTES
import com.fizaan.timetracker.export.ExportFormat
import com.fizaan.timetracker.util.formatDuration
import com.fizaan.timetracker.util.formatLongDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Which tool is currently open within the Tools section. */
private enum class Tool { NONE, FREQUENCY, TREND, HEATMAP, BATCH, TRANSFER }

@Composable
fun ToolsScreen(
    state: ToolsState,
    onMenu: () -> Unit,
    trend: TrendActions,
    heat: HeatActions,
    onSetFreqActivity: (Int?) -> Unit,
    onSetFreqFrom: (LocalDate) -> Unit,
    onSetFreqTo: (LocalDate) -> Unit,
    onCompute: () -> Unit,
    onSetExportFrom: (LocalDate) -> Unit,
    onSetExportTo: (LocalDate) -> Unit,
    onSetExportFormat: (ExportFormat) -> Unit,
    onExport: () -> Unit,
    onPrintHandled: () -> Unit,
    onImport: (Uri) -> Unit,
    onClearImport: () -> Unit,
    onSetBatchActivity: (Int?) -> Unit,
    onSetBatchTag: (String?) -> Unit,
    onSetBatchMin: (String) -> Unit,
    onSetBatchMax: (String) -> Unit,
    onSetBatchFrom: (LocalDate) -> Unit,
    onSetBatchTo: (LocalDate) -> Unit,
    onBatchSearch: () -> Unit,
    onAskBatch: (BatchAction, Int?, List<String>, String?) -> Unit,
    onAskBatchActivityName: (String) -> Unit,
    onConfirmBatch: () -> Unit,
    onDismissBatch: () -> Unit,
    onClearError: () -> Unit,
) {
    var tool by remember { mutableStateOf(Tool.NONE) }

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
            if (tool == Tool.NONE) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, "Menu", tint = MaterialTheme.colorScheme.onBackground)
                }
            } else {
                IconButton(onClick = { tool = Tool.NONE }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Text(
                text = when (tool) {
                    Tool.FREQUENCY -> "Frequency calculator"
                    Tool.TREND -> "Rolling average"
                    Tool.HEATMAP -> "Frequency calendar"
                    Tool.BATCH -> "Batch edit"
                    Tool.TRANSFER -> "Import/Export"
                    Tool.NONE -> "Tools"
                },
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
        }

        CacheBanner(state.cached)

        Box(modifier = Modifier.weight(1f)) {
            when (tool) {
                Tool.NONE -> ToolList(
                    state = state,
                    onOpenFrequency = { tool = Tool.FREQUENCY },
                    onOpenTrend = { tool = Tool.TREND },
                    onOpenHeat = { tool = Tool.HEATMAP },
                    onOpenBatch = { tool = Tool.BATCH },
                    onOpenTransfer = { tool = Tool.TRANSFER },
                )
                Tool.FREQUENCY -> FrequencyTool(
                    state = state,
                    onSetActivity = onSetFreqActivity,
                    onSetFrom = onSetFreqFrom,
                    onSetTo = onSetFreqTo,
                    onCompute = onCompute,
                )
                Tool.TREND -> TrendTool(state = state, actions = trend)
                Tool.HEATMAP -> HeatCalendarTool(state = state, actions = heat)
                Tool.BATCH -> BatchTool(
                    state = state,
                    onSetActivity = onSetBatchActivity,
                    onSetTag = onSetBatchTag,
                    onSetMin = onSetBatchMin,
                    onSetMax = onSetBatchMax,
                    onSetFrom = onSetBatchFrom,
                    onSetTo = onSetBatchTo,
                    onSearch = onBatchSearch,
                    onAsk = onAskBatch,
                    onAskActivityName = onAskBatchActivityName,
                    onConfirm = onConfirmBatch,
                    onDismiss = onDismissBatch,
                )
                Tool.TRANSFER -> ImportExportTool(
                    state = state,
                    onSetFrom = onSetExportFrom,
                    onSetTo = onSetExportTo,
                    onSetFormat = onSetExportFormat,
                    onExport = onExport,
                    onPrintHandled = onPrintHandled,
                    onImport = onImport,
                    onClearImport = onClearImport,
                )
            }
            if (state.loading || state.computing) {
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
private fun ToolList(
    state: ToolsState,
    onOpenFrequency: () -> Unit,
    onOpenTrend: () -> Unit,
    onOpenHeat: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenTransfer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ToolCard(
            title = "Frequency calculator",
            subtitle = "Average how often and how long you do an activity",
            icon = Icons.Filled.BarChart,
            onClick = onOpenFrequency,
        )
        Spacer(Modifier.height(12.dp))
        ToolCard(
            title = "Rolling average",
            subtitle = "How an activity has moved, a point a day",
            icon = Icons.AutoMirrored.Filled.ShowChart,
            onClick = onOpenTrend,
        )
        Spacer(Modifier.height(12.dp))
        ToolCard(
            title = "Frequency calendar",
            subtitle = "A month of days, darker where it happened more",
            icon = Icons.Filled.CalendarMonth,
            onClick = onOpenHeat,
        )
        Spacer(Modifier.height(12.dp))
        ToolCard(
            title = "Batch edit",
            subtitle = "Retag, recolour, move or delete many entries at once",
            icon = Icons.Filled.EditNote,
            onClick = onOpenBatch,
        )
        // Local-only installs have neither half of this: the feature exists
        // because a server-backed timesheet is the thing people move around.
        if (!state.serverless) {
            Spacer(Modifier.height(12.dp))
            ToolCard(
                title = "Import/Export",
                subtitle = "A date range out as CSV, Excel, PDF or print — or a file back in",
                icon = Icons.Filled.SwapVert,
                onClick = onOpenTransfer,
            )
        }
        Spacer(Modifier.height(24.dp))
        StorageFooter(state)
    }
}

/**
 * How much timesheet history is held on the device. Backed by a server that is
 * a capped offline copy; local-only it is the database itself, and uncapped.
 */
@Composable
private fun StorageFooter(state: ToolsState) {
    Text(
        text = if (state.serverless) {
            "On this device: ${formatBytes(state.cacheBytes)} · " +
                "${state.cacheEntries} entries · no limit"
        } else {
            "Saved data: ${formatBytes(state.cacheBytes)} of " +
                "${formatBytes(CACHE_MAX_BYTES)} · ${state.cacheEntries} entries"
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun ToolCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        Icon(
            Icons.Filled.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun FrequencyTool(
    state: ToolsState,
    onSetActivity: (Int?) -> Unit,
    onSetFrom: (LocalDate) -> Unit,
    onSetTo: (LocalDate) -> Unit,
    onCompute: () -> Unit,
) {
    var actMenu by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Activity")
        Box {
            val actName = state.activities.firstOrNull { it.id == state.freqActivityId }?.name
            OutlinedButton(onClick = { actMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(actName ?: "Select an activity", modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = actMenu, onDismissRequest = { actMenu = false }) {
                state.activities.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a.name) },
                        onClick = { onSetActivity(a.id); actMenu = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Time frame")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFrom = true }, modifier = Modifier.weight(1f)) {
                Text(state.freqFrom.format(dateFmt))
            }
            Text(
                "→",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = { pickTo = true }, modifier = Modifier.weight(1f)) {
                Text(state.freqTo.format(dateFmt))
            }
        }
        QuickRanges(onSetFrom = onSetFrom, onSetTo = onSetTo)

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onCompute,
            enabled = state.freqActivityId != null && !state.computing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.computing) "Calculating…" else "Calculate")
        }

        Spacer(Modifier.height(20.dp))
        state.freqResult?.let { r ->
            if (r.activityId == state.freqActivityId) ResultCard(r, state.cached)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickFrom) {
        ToolDateDialog(initial = state.freqFrom, onDismiss = { pickFrom = false }) { onSetFrom(it) }
    }
    if (pickTo) {
        ToolDateDialog(initial = state.freqTo, onDismiss = { pickTo = false }) { onSetTo(it) }
    }
}

/**
 * One line of the averages table: how often and how long, per period.
 *
 * [extrapolated] is false when the range is shorter than the period itself —
 * a fortnight cannot say what a year looks like — and [time] is then simply
 * everything logged so far, left as the honest answer rather than multiplied up
 * into one that isn't.
 */
private data class FreqRow(
    val label: String,
    val days: Double,
    val sessions: Double,
    val timeSeconds: Long,
    val extrapolated: Boolean,
)

@Composable
private fun ResultCard(r: FreqResult, cached: CacheInfo?) {
    // Every day of the range divides, including the empty ones, so these read
    // as a rate rather than an intensity: the longer the quiet stretch, the
    // lower the daily figure. The coarser rows are that same daily rate over a
    // week, an average month and a year.
    val span = r.spanDays.coerceAtLeast(1).toDouble()
    // A period divides by its average length but is asked for in whole days:
    // thirty days of range is a month's worth of evidence, and refusing to
    // average it because a month is 30.44 days long would be pedantry.
    val rows = remember(r) {
        listOf(
            Triple("Daily", 1.0, 1),
            Triple("Weekly", 7.0, 7),
            Triple("Monthly", 30.44, 30),
            Triple("Yearly", 365.25, 365),
        ).map { (label, days, minDays) ->
            val enough = r.spanDays >= minDays
            FreqRow(
                label = label,
                days = days,
                sessions = r.sessions / span * days,
                timeSeconds = if (enough) (r.totalSeconds / span * days).toLong()
                else r.totalSeconds,
                extrapolated = enough,
            )
        }
    }
    val partial = rows.filter { !it.extrapolated }
    // Time spent, projected to a full year over the selected span.
    val yearlySeconds = (r.totalSeconds * 365.25 / span).toLong()
    val yearShare = yearlySeconds / (365.25 * 24 * 3600) * 100.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        cached?.let {
            Text(
                "Computed from saved data (${formatSavedAt(it.savedAt)}), not the server",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = CacheAmber,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Over ${r.spanDays} day${if (r.spanDays == 1) "" else "s"} (${r.activeDays} with " +
                    "sessions): ${r.sessions} session${if (r.sessions == 1) "" else "s"}, " +
                    formatDuration(r.totalSeconds) + " total",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
            )
            FreqHelp(r, partial)
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            HeaderCell("Average", 0.9f)
            HeaderCell("Sessions", 0.8f)
            HeaderCell("Time", 1.3f)
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
        )
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                BodyCell(row.label, 0.9f, bold = true)
                BodyCell(formatFreq(row.sessions), 0.8f)
                BodyCell(
                    text = formatLongDuration(row.timeSeconds) +
                        if (row.extrapolated) "" else " so far",
                    weight = 1.3f,
                    color = if (row.extrapolated) MaterialTheme.colorScheme.onBackground
                    else CacheAmber,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Projected yearly time",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatLongDuration(yearlySeconds),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "${formatPercent(yearShare)} of the year",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}

/**
 * The explanations, behind a question mark rather than under the numbers.
 *
 * Everything here was once four paragraphs on the screen, read once and then in
 * the way for good. What stays outside is anything that qualifies *this* result
 * — the amber "so far" cells, the saved-data notice — while the reasoning
 * behind them is a tap away. The mark itself turns amber when there is a caveat
 * inside it, so a result that needs reading twice still looks like one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreqHelp(r: FreqResult, partial: List<FreqRow>) {
    val caveats = freqCaveats(r, partial)
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        state = tooltip,
        tooltip = {
            RichTooltip(
                title = { Text("How these are worked out") },
                action = {
                    TextButton(onClick = { tooltip.dismiss() }) { Text("Got it") }
                },
            ) {
                Text((listOf(FreqMethod) + caveats).joinToString("\n\n"))
            }
        },
    ) {
        IconButton(onClick = { scope.launch { tooltip.show() } }) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = "How these are worked out",
                tint = if (caveats.isEmpty()) {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                } else {
                    CacheAmber
                },
            )
        }
    }
}

private const val FreqMethod =
    "Rates divide by every day in the range, empty ones included, so they read " +
        "as a rate rather than an intensity: a fortnight off pulls the average " +
        "down. The count of days that did have sessions is there as context, " +
        "not as the divisor. Long spans are written in days, hours and minutes " +
        "— twelve and a half days is a length of time in a way that 300 hours " +
        "isn't."

/** Under this much history, a rate says more about the window than the habit. */
private const val ShortHistoryDays = 30

/**
 * What is true of this result in particular: a range too short to average over,
 * and a history too short to trust. Both used to sit under the table in amber.
 */
private fun freqCaveats(r: FreqResult, partial: List<FreqRow>): List<String> = buildList {
    // The periods nest, so naming the shortest one that doesn't fit explains
    // every row that fell back at once.
    partial.minByOrNull { it.days }?.let { shortest ->
        add(
            "The range is shorter than ${periodNoun(shortest.days)}, so the " +
                partial.joinToString(" and ") { it.label.lowercase() } +
                " times are everything logged so far, not an average.",
        )
    }
    when {
        r.sessions == 0 ->
            add("No sessions for this activity in this range, so every rate above is zero.")
        r.observedDays < ShortHistoryDays -> {
            val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
            add(
                "Only ${r.observedDays} day${if (r.observedDays == 1) "" else "s"} of data " +
                    "for this activity — the first session here was on " +
                    "${r.firstEntry?.format(fmt)}. Rates from a window this short swing on " +
                    "a single busy or quiet week, and the yearly figures especially may be " +
                    "well off.",
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BodyCell(
    text: String,
    weight: Float,
    bold: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        color = color,
        modifier = Modifier.weight(weight),
    )
}

/** How a period reads in a sentence: "a week", "a month". */
private fun periodNoun(days: Double): String = when {
    days < 7.0 -> "a day"
    days < 30.0 -> "a week"
    days < 365.0 -> "a month"
    else -> "a year"
}

/** "10" for whole counts, "3.3" otherwise. */
private fun formatFreq(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

/** Keeps small shares legible: "0.04%", "0.9%", "13%". */
private fun formatPercent(v: Double): String = when {
    v <= 0.0 -> "0%"
    v < 0.1 -> "%.2f%%".format(v)
    v < 10 -> "%.1f%%".format(v)
    else -> "%.0f%%".format(v)
}

/**
 * The ranges worth one tap, shared by every tool that takes a date range.
 *
 * A week and a fortnight are here because a habit a fortnight old has no
 * thirty-day answer — and asking for one would only report that most of the
 * range was empty. They wrap rather than shrink: five of them do not fit across
 * a narrow phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickRanges(onSetFrom: (LocalDate) -> Unit, onSetTo: (LocalDate) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(7, 15, 30, 90, 365).forEach { days ->
            QuickRange(if (days == 365) "1y" else "${days}d") {
                onSetFrom(LocalDate.now().minusDays((days - 1).toLong()))
                onSetTo(LocalDate.now())
            }
        }
    }
}

@Composable
private fun QuickRange(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(label, fontSize = 13.sp) }
}

/** Shared by the tools that need a single date — frequency, batch edit, export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}
