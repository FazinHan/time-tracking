package com.fizaan.timetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.ToolsState
import com.fizaan.timetracker.chart.ChartFormat
import com.fizaan.timetracker.chart.TrendMetric
import com.fizaan.timetracker.chart.formatTick
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Everything the plotter can ask the ViewModel to do. */
data class TrendActions(
    val setActivity: (Int?) -> Unit,
    val setMetric: (TrendMetric) -> Unit,
    val setWindow: (Int) -> Unit,
    val setFrom: (LocalDate) -> Unit,
    val setTo: (LocalDate) -> Unit,
    val setColor: (String?) -> Unit,
    val plot: () -> Unit,
    val export: (ChartFormat, Boolean, Int) -> Unit,
)

/**
 * A rolling average of one activity over time, drawn the way a share price is:
 * a point per day, each one the average of the days behind it, so the line says
 * which way a habit is going rather than what happened on a particular Tuesday.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendTool(state: ToolsState, actions: TrendActions) {
    val trend = state.trend
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
            val name = state.activities.firstOrNull { it.id == trend.activityId }?.name
            OutlinedButton(onClick = { actMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(name ?: "Select an activity", modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = actMenu, onDismissRequest = { actMenu = false }) {
                state.activities.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a.name) },
                        onClick = { actions.setActivity(a.id); actMenu = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("What to average")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TrendMetric.entries.forEachIndexed { i, metric ->
                SegmentedButton(
                    selected = trend.metric == metric,
                    onClick = { actions.setMetric(metric) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = i, count = TrendMetric.entries.size,
                    ),
                ) { Text(metric.label) }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Rolling window")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(3, 7, 30).forEach { days ->
                WindowChip(days, trend.window == days) { actions.setWindow(days) }
            }
            var custom by remember { mutableStateOf("") }
            OutlinedTextField(
                value = custom,
                onValueChange = { text ->
                    custom = text.filter { it.isDigit() }.take(3)
                    custom.toIntOrNull()?.takeIf { it in 1..365 }?.let(actions.setWindow)
                },
                label = { Text("days", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
        }
        Text(
            "Each point is the average of that day and the ${trend.window - 1} before it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("Time frame")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFrom = true }, modifier = Modifier.weight(1f)) {
                Text(trend.from.format(dateFmt))
            }
            Text(
                "→",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = { pickTo = true }, modifier = Modifier.weight(1f)) {
                Text(trend.to.format(dateFmt))
            }
        }
        QuickRanges(onSetFrom = actions.setFrom, onSetTo = actions.setTo)

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = actions.plot,
            enabled = trend.activityId != null && !trend.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (trend.running) "Plotting…" else "Plot")
        }

        trend.chart?.let { chart ->
            val argb = chartColor(trend.activityId, state.activities, trend.colorOverride)
            Spacer(Modifier.height(20.dp))
            TrendCanvas(chart, argb)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Readout("Average", formatTick(chart.average, chart.metric), Modifier.weight(1f))
                chart.peak?.let {
                    Readout(
                        "Peak",
                        formatTick(it.value, chart.metric) + " · " +
                            it.date.format(DateTimeFormatter.ofPattern("d MMM")),
                        Modifier.weight(1.4f),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Line colour")
            Text(
                "Taken from the activity. Changing it here changes this plot only.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ColorSwatches(state.colorChoices, trend.colorOverride, size = 30.dp) {
                actions.setColor(it)
            }
            if (trend.colorOverride != null) {
                TextButton(onClick = { actions.setColor(null) }) {
                    Text("Back to the activity's colour", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            ChartExportRow(trend.exporting, trend.saved) { format, transparent ->
                actions.export(format, transparent, argb)
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (pickFrom) {
        ToolDateDialog(initial = trend.from, onDismiss = { pickFrom = false }) { actions.setFrom(it) }
    }
    if (pickTo) {
        ToolDateDialog(initial = trend.to, onDismiss = { pickTo = false }) { actions.setTo(it) }
    }
}

@Composable
private fun WindowChip(days: Int, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            "${days}d",
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Readout(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}
