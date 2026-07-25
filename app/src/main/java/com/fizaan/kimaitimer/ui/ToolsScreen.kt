package com.fizaan.kimaitimer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.CacheInfo
import com.fizaan.kimaitimer.FreqResult
import com.fizaan.kimaitimer.ToolsState
import com.fizaan.kimaitimer.data.CACHE_MAX_BYTES
import com.fizaan.kimaitimer.util.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Which tool is currently open within the Tools section. */
private enum class Tool { NONE, FREQUENCY }

@Composable
fun ToolsScreen(
    state: ToolsState,
    onMenu: () -> Unit,
    onSetFreqActivity: (Int?) -> Unit,
    onSetFreqFrom: (LocalDate) -> Unit,
    onSetFreqTo: (LocalDate) -> Unit,
    onCompute: () -> Unit,
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
                text = if (tool == Tool.FREQUENCY) "Frequency calculator" else "Tools",
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
                )
                Tool.FREQUENCY -> FrequencyTool(
                    state = state,
                    onSetActivity = onSetFreqActivity,
                    onSetFrom = onSetFreqFrom,
                    onSetTo = onSetFreqTo,
                    onCompute = onCompute,
                )
            }
            if (state.loading || state.computing) {
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
private fun ToolList(state: ToolsState, onOpenFrequency: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        ToolCard(
            title = "Frequency calculator",
            subtitle = "Average how often and how long you do an activity",
            icon = Icons.Filled.BarChart,
            onClick = onOpenFrequency,
        )
        Spacer(Modifier.weight(1f))
        StorageFooter(state)
    }
}

/**
 * How much timesheet history is held on the device. The cap is what keeps the
 * tools usable offline without letting the store grow without bound.
 */
@Composable
private fun StorageFooter(state: ToolsState) {
    Text(
        text = "Saved data: ${formatBytes(state.cacheBytes)} of " +
            "${formatBytes(CACHE_MAX_BYTES)} · ${state.cacheEntries} entries",
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
        Icon(icon, null, tint = KimaiGreen)
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickRange("30d") { onSetFrom(LocalDate.now().minusDays(29)); onSetTo(LocalDate.now()) }
            QuickRange("90d") { onSetFrom(LocalDate.now().minusDays(89)); onSetTo(LocalDate.now()) }
            QuickRange("1y") { onSetFrom(LocalDate.now().minusDays(364)); onSetTo(LocalDate.now()) }
        }

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
        FreqDateDialog(initial = state.freqFrom, onDismiss = { pickFrom = false }) { onSetFrom(it) }
    }
    if (pickTo) {
        FreqDateDialog(initial = state.freqTo, onDismiss = { pickTo = false }) { onSetTo(it) }
    }
}

@Composable
private fun ResultCard(r: FreqResult, cached: CacheInfo?) {
    if (r.activeDays <= 0) return
    // Each frequency = sessions ÷ number of active periods of that granularity,
    // so empty days/weeks/months/years don't dilute the count. With all data in
    // one week/month/year, weekly/monthly/yearly all equal the total sessions.
    val rows = listOf(
        "Daily" to r.sessions / r.activeDays.toDouble(),
        "Weekly" to r.sessions / r.activeWeeks.coerceAtLeast(1).toDouble(),
        "Monthly" to r.sessions / r.activeMonths.coerceAtLeast(1).toDouble(),
        "Yearly" to r.sessions / r.activeYears.coerceAtLeast(1).toDouble(),
    )
    // Time spent, projected to a full year over the selected span.
    val span = (ChronoUnit.DAYS.between(r.from, r.to) + 1).coerceAtLeast(1).toDouble()
    val yearlySeconds = (r.totalSeconds * 365.25 / span).toLong()

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
        Text(
            "Over ${r.activeDays} active day${if (r.activeDays == 1) "" else "s"}: ${r.sessions} session" +
                "${if (r.sessions == 1) "" else "s"}, ${formatDuration(r.totalSeconds)} total",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            HeaderCell("Average", 1f)
            HeaderCell("Frequency", 1f)
        }
        androidx.compose.material3.Divider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
        )
        rows.forEach { (label, count) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                BodyCell(label, 1f, bold = true)
                BodyCell(formatFreq(count), 1f)
            }
        }
        androidx.compose.material3.Divider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Projected yearly time",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(yearlySeconds),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = KimaiGreen,
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
) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.weight(weight),
    )
}

/** "10" for whole counts, "3.3" otherwise. */
private fun formatFreq(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun QuickRange(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreqDateDialog(
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
