package com.fizaan.kimaitimer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.SheetState
import com.fizaan.kimaitimer.data.Activity
import com.fizaan.kimaitimer.data.TimesheetEntry
import com.fizaan.kimaitimer.util.entrySeconds
import com.fizaan.kimaitimer.util.formatDuration
import com.fizaan.kimaitimer.util.formatKimai
import com.fizaan.kimaitimer.util.parseKimaiLocal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun SheetScreen(
    state: SheetState,
    onMenu: () -> Unit,
    onRefresh: () -> Unit,
    onOpenEdit: (TimesheetEntry) -> Unit,
    onDismissEdit: () -> Unit,
    onSave: (entryId: Int, activityId: Int, beginIso: String, endIso: String?, newColor: String?) -> Unit,
    onClearError: () -> Unit,
) {
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMM yyyy") }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val now = System.currentTimeMillis()

    // Group by calendar day, newest first.
    val grouped = remember(state.entries) {
        state.entries
            .sortedByDescending { it.begin }
            .groupBy { parseKimaiLocal(it.begin)?.toLocalDate() ?: LocalDate.MIN }
            .toList()
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
                text = "Timesheet",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                grouped.forEach { (date, entries) ->
                    item(key = "h$date") {
                        Text(
                            text = date.format(dayFmt),
                            fontSize = 13.sp,
                            color = KimaiGreen,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(entries.size, key = { i -> entries[i].id }) { i ->
                        EntryRow(
                            entry = entries[i],
                            activities = state.activities,
                            timeFmt = timeFmt,
                            now = now,
                            onClick = { onOpenEdit(entries[i]) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
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
                        .clickable { onClearError() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(err, color = Color.White)
                }
            }
        }
    }

    state.editing?.let { entry ->
        EditEntryDialog(
            entry = entry,
            activities = state.activities,
            colorChoices = state.colorChoices,
            saving = state.saving,
            onSave = onSave,
            onDismiss = onDismissEdit,
        )
    }
}

@Composable
private fun EntryRow(
    entry: TimesheetEntry,
    activities: List<Activity>,
    timeFmt: DateTimeFormatter,
    now: Long,
    onClick: () -> Unit,
) {
    val act = activities.firstOrNull { it.id == entry.activity }
    val begin = parseKimaiLocal(entry.begin)
    val end = parseKimaiLocal(entry.end)
    val secs = entrySeconds(entry.begin, entry.end, entry.duration, now)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(12.dp)
                .background(colorForActivity(entry.activity, activities), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = act?.name ?: "#${entry.activity}",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val tags = entry.tags?.filter { it.isNotBlank() }.orEmpty()
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString(" · "),
                    fontSize = 11.sp,
                    color = KimaiGreen,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = buildString {
                    append(begin?.format(timeFmt) ?: "?")
                    append(" – ")
                    append(end?.format(timeFmt) ?: "now")
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Text(
                text = if (entry.end == null) "${formatDuration(secs)} · running"
                else formatDuration(secs),
                fontSize = 12.sp,
                color = if (entry.end == null) KimaiRed
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        }
    }
}

// ---------------- Edit dialog ----------------

/**
 * Offline fallback for the swatch picker — a copy of Kimai's default palette.
 * The live list comes from GET /api/config/colors; the server rejects any
 * color not in its configured choices.
 */
private val DefaultColorChoices = mapOf(
    "Silver" to "#c0c0c0", "Gray" to "#808080", "Maroon" to "#800000",
    "Brown" to "#a52a2a", "Red" to "#ff0000", "Orange" to "#ffa500",
    "Gold" to "#ffd700", "Yellow" to "#ffff00", "Peach" to "#ffdab9",
    "Khaki" to "#f0e68c", "Olive" to "#808000", "Lime" to "#00ff00",
    "Jelly" to "#9acd32", "Green" to "#008000", "Teal" to "#008080",
    "Aqua" to "#00ffff", "LightBlue" to "#add8e6", "DeepSky" to "#00bfff",
    "Dodger" to "#1e90ff", "Blue" to "#0000ff", "Navy" to "#000080",
    "Purple" to "#800080", "Fuchsia" to "#ff00ff", "Violet" to "#ee82ee",
    "Rose" to "#ffe4e1", "Lavender" to "#E6E6FA",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditEntryDialog(
    entry: TimesheetEntry,
    activities: List<Activity>,
    colorChoices: Map<String, String>,
    saving: Boolean,
    onSave: (entryId: Int, activityId: Int, beginIso: String, endIso: String?, newColor: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val act = activities.firstOrNull { it.id == entry.activity }
    val running = entry.end == null

    var begin by remember(entry.id) {
        mutableStateOf(parseKimaiLocal(entry.begin) ?: LocalDateTime.now())
    }
    var end by remember(entry.id) { mutableStateOf(parseKimaiLocal(entry.end)) }
    var useDuration by remember(entry.id) { mutableStateOf(false) }
    var durationText by remember(entry.id) {
        val secs = entrySeconds(entry.begin, entry.end, entry.duration, System.currentTimeMillis())
        mutableStateOf("%d:%02d".format(secs / 3600, (secs % 3600) / 60))
    }
    var colorHex by remember(entry.id) { mutableStateOf(act?.color) }

    var pickBeginDate by remember { mutableStateOf(false) }
    var pickBeginTime by remember { mutableStateOf(false) }
    var pickEndDate by remember { mutableStateOf(false) }
    var pickEndTime by remember { mutableStateOf(false) }

    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }

    val parsedDuration = parseDurationText(durationText)
    val endValid = if (useDuration) parsedDuration != null else true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(act?.name ?: "Entry") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FieldLabel("Start")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickBeginDate = true }) {
                        Text(begin.format(dateFmt))
                    }
                    OutlinedButton(onClick = { pickBeginTime = true }) {
                        Text(begin.format(timeFmt))
                    }
                }

                Spacer(Modifier.height(14.dp))
                FieldLabel("End")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !useDuration,
                        onClick = { useDuration = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("End time") }
                    SegmentedButton(
                        selected = useDuration,
                        onClick = { useDuration = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Duration") }
                }
                Spacer(Modifier.height(8.dp))
                if (useDuration) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it },
                        label = { Text("Duration (h:mm)") },
                        singleLine = true,
                        isError = parsedDuration == null,
                    )
                } else {
                    if (running && end == null) {
                        Text(
                            "Still running — pick an end time or a duration to stop it, " +
                                "or save with start only to keep it running.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { end = LocalDateTime.now(); pickEndTime = true }) {
                            Text("Set end time…")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickEndDate = true }) {
                                Text(end?.format(dateFmt) ?: "date")
                            }
                            OutlinedButton(onClick = { pickEndTime = true }) {
                                Text(end?.format(timeFmt) ?: "time")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                FieldLabel("Activity colour (applies everywhere)")
                val swatches = colorChoices.ifEmpty { DefaultColorChoices }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    swatches.values.forEach { hex ->
                        val c = parseHexColor(hex) ?: return@forEach
                        val selected = colorHex?.equals(hex, ignoreCase = true) == true
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(c, CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color.White
                                    else Color.White.copy(alpha = 0.25f),
                                    shape = CircleShape,
                                )
                                .clickable { colorHex = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                enabled = !saving && endValid,
                onClick = {
                    val endIso: String? = if (useDuration) {
                        parsedDuration?.let { formatKimai(begin.plusSeconds(it)) }
                    } else {
                        end?.let { formatKimai(it) }
                    }
                    val newColor =
                        colorHex?.takeIf { !it.equals(act?.color, ignoreCase = true) }
                    onSave(entry.id, entry.activity, formatKimai(begin), endIso, newColor)
                },
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (pickBeginDate) {
        DateDialog(initial = begin.toLocalDate(), onDismiss = { pickBeginDate = false }) { d ->
            begin = LocalDateTime.of(d, begin.toLocalTime())
        }
    }
    if (pickBeginTime) {
        TimeDialog(initial = begin, onDismiss = { pickBeginTime = false }) { h, m ->
            begin = begin.withHour(h).withMinute(m).withSecond(0)
        }
    }
    if (pickEndDate) {
        DateDialog(
            initial = (end ?: begin).toLocalDate(),
            onDismiss = { pickEndDate = false },
        ) { d ->
            end = LocalDateTime.of(d, (end ?: begin).toLocalTime())
        }
    }
    if (pickEndTime) {
        TimeDialog(initial = end ?: begin, onDismiss = { pickEndTime = false }) { h, m ->
            end = (end ?: begin).withHour(h).withMinute(m).withSecond(0)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** "1:30", "0:45" or plain minutes ("90") → seconds. */
private fun parseDurationText(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (":" in t) {
        val parts = t.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        if (h < 0 || m < 0 || m > 59) return null
        return h * 3600 + m * 60
    }
    val mins = t.toLongOrNull() ?: return null
    return if (mins >= 0) mins * 60 else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onPick: (hour: Int, minute: Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour, state.minute); onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
