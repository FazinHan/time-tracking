package com.fizaan.timetracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.ToolsState
import com.fizaan.timetracker.chart.ChartFormat
import com.fizaan.timetracker.util.formatDuration
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Everything the calendar can ask the ViewModel to do. */
data class HeatActions(
    val setActivity: (Int?) -> Unit,
    val shiftMonth: (Long) -> Unit,
    val setColor: (String?) -> Unit,
    val export: (ChartFormat, Boolean, Int) -> Unit,
)

/**
 * A month of one activity, a box per day, darker where it happened more often.
 *
 * A rate answers how much; this answers *when* — the weeks it was kept up and
 * the weeks it wasn't are a shape you can see at a glance and can't read off a
 * table of averages.
 */
@Composable
fun HeatCalendarTool(state: ToolsState, actions: HeatActions) {
    val heat = state.heat
    var actMenu by remember { mutableStateOf(false) }
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Activity")
        Box {
            val name = state.activities.firstOrNull { it.id == heat.activityId }?.name
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

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { actions.shiftMonth(-1) }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Previous month",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                heat.month.format(monthFmt),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // There is nothing recorded in a month that hasn't happened.
            val atToday = heat.month >= YearMonth.now()
            IconButton(onClick = { actions.shiftMonth(1) }, enabled = !atToday) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, "Next month",
                    tint = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (atToday) 0.3f else 1f,
                    ),
                )
            }
        }

        when {
            heat.activityId == null -> Hint("Pick an activity to see its month.")
            heat.running && heat.chart == null -> Hint("Reading the month…")
            else -> heat.chart?.let { chart ->
                val argb = chartColor(heat.activityId, state.activities, heat.colorOverride)
                MonthCanvas(chart, argb)
                Text(
                    if (chart.totalSessions == 0) {
                        "Nothing recorded this month."
                    } else {
                        "${chart.totalSessions} session" +
                            "${if (chart.totalSessions == 1) "" else "s"} on " +
                            "${chart.activeDays} of ${chart.month.lengthOfMonth()} days · " +
                            formatDuration(chart.totalSeconds) + " in total"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(20.dp))
                SectionLabel("Calendar colour")
                Text(
                    "Taken from the activity. Changing it here changes this calendar only.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ColorSwatches(state.colorChoices, heat.colorOverride, size = 30.dp) {
                    actions.setColor(it)
                }
                if (heat.colorOverride != null) {
                    TextButton(onClick = { actions.setColor(null) }) {
                        Text("Back to the activity's colour", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))
                ChartExportRow(heat.exporting, heat.saved) { format, transparent ->
                    actions.export(format, transparent, argb)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 24.dp),
    )
}
