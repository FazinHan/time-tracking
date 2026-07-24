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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.SheetPeriod
import com.fizaan.kimaitimer.SheetState
import com.fizaan.kimaitimer.UNTAGGED
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
    onSave: (entryId: Int, activityId: Int, beginIso: String, endIso: String?, newColor: String?, description: String, tags: String) -> Unit,
    onClearError: () -> Unit,
    onSetActivityFilter: (Int?) -> Unit,
    onSetTagFilter: (String?) -> Unit,
    onSetPeriod: (SheetPeriod) -> Unit,
    onSetDate: (LocalDate) -> Unit,
    onClearFilters: () -> Unit,
) {
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMM yyyy") }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val now = System.currentTimeMillis()

    // Apply filters, then group by calendar day, newest first.
    val grouped = remember(
        state.entries, state.filterActivityId, state.filterTag, state.filterFrom, state.filterTo,
    ) {
        state.entries
            .filter { e -> entryMatchesFilters(e, state) }
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

        FilterBar(
            state = state,
            onSetActivityFilter = onSetActivityFilter,
            onSetTagFilter = onSetTagFilter,
            onSetPeriod = onSetPeriod,
            onSetDate = onSetDate,
            onClearFilters = onClearFilters,
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (grouped.isEmpty() && !state.loading) {
                    item {
                        Text(
                            "No entries match the current filters.",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 32.dp),
                        )
                    }
                }
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
            allTags = state.allTags,
            colorChoices = state.colorChoices,
            saving = state.saving,
            onSave = onSave,
            onDismiss = onDismissEdit,
        )
    }
}

private fun entryMatchesFilters(e: TimesheetEntry, state: SheetState): Boolean {
    if (state.filterActivityId != null && e.activity != state.filterActivityId) return false
    state.filterTag?.let { wanted ->
        val tags = e.tags?.filter { it.isNotBlank() }.orEmpty()
        val ok = if (wanted == UNTAGGED) tags.isEmpty() else tags.contains(wanted)
        if (!ok) return false
    }
    if (state.filterFrom != null || state.filterTo != null) {
        val d = parseKimaiLocal(e.begin)?.toLocalDate() ?: return false
        if (state.filterFrom != null && d.isBefore(state.filterFrom)) return false
        if (state.filterTo != null && d.isAfter(state.filterTo)) return false
    }
    return true
}

// ---------------- Filter bar ----------------

@Composable
private fun FilterBar(
    state: SheetState,
    onSetActivityFilter: (Int?) -> Unit,
    onSetTagFilter: (String?) -> Unit,
    onSetPeriod: (SheetPeriod) -> Unit,
    onSetDate: (LocalDate) -> Unit,
    onClearFilters: () -> Unit,
) {
    var actMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var periodMenu by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val rangeFmt = remember { DateTimeFormatter.ofPattern("d MMM") }

    val anyFilter = state.filterActivityId != null || state.filterTag != null ||
        state.filterFrom != null || state.filterTo != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            val actName = state.activities.firstOrNull { it.id == state.filterActivityId }?.name
            FilterChip(
                selected = state.filterActivityId != null,
                onClick = { actMenu = true },
                label = { Text(actName ?: "Activity") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = actMenu, onDismissRequest = { actMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All activities") },
                    onClick = { onSetActivityFilter(null); actMenu = false },
                )
                state.activities.sortedBy { it.name.lowercase() }.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a.name) },
                        onClick = { onSetActivityFilter(a.id); actMenu = false },
                    )
                }
            }
        }

        Box {
            val tagLabel = when (state.filterTag) {
                null -> "Tag"
                UNTAGGED -> "untagged"
                else -> state.filterTag
            }
            FilterChip(
                selected = state.filterTag != null,
                onClick = { tagMenu = true },
                label = { Text(tagLabel) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All tags") },
                    onClick = { onSetTagFilter(null); tagMenu = false },
                )
                state.allTags.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t) },
                        onClick = { onSetTagFilter(t); tagMenu = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text("untagged") },
                    onClick = { onSetTagFilter(UNTAGGED); tagMenu = false },
                )
            }
        }

        Box {
            val periodLabel = when (state.filterPreset) {
                SheetPeriod.ALL -> "Period"
                SheetPeriod.DAY -> "Today"
                SheetPeriod.WEEK -> "This week"
                SheetPeriod.MONTH -> "This month"
                SheetPeriod.YEAR -> "This year"
                SheetPeriod.CUSTOM -> {
                    val f = state.filterFrom
                    val t = state.filterTo
                    when {
                        f != null && t != null && f == t -> f.format(rangeFmt)
                        f != null && t != null -> "${f.format(rangeFmt)} – ${t.format(rangeFmt)}"
                        else -> "Custom"
                    }
                }
            }
            FilterChip(
                selected = state.filterPreset != SheetPeriod.ALL,
                onClick = { periodMenu = true },
                label = { Text(periodLabel) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All time") },
                    onClick = { onSetPeriod(SheetPeriod.ALL); periodMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("Today") },
                    onClick = { onSetPeriod(SheetPeriod.DAY); periodMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("This week") },
                    onClick = { onSetPeriod(SheetPeriod.WEEK); periodMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("This month") },
                    onClick = { onSetPeriod(SheetPeriod.MONTH); periodMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("This year") },
                    onClick = { onSetPeriod(SheetPeriod.YEAR); periodMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("Pick date…") },
                    onClick = { periodMenu = false; pickDate = true },
                )
            }
        }

        if (anyFilter) {
            IconButton(onClick = onClearFilters) {
                Icon(
                    Icons.Filled.Close, "Clear filters",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
    }

    if (pickDate) {
        DateDialog(
            initial = state.filterFrom ?: LocalDate.now(),
            onDismiss = { pickDate = false },
        ) { d -> onSetDate(d) }
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
            if (!entry.description.isNullOrBlank()) {
                Text(
                    text = entry.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
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
    allTags: List<String>,
    colorChoices: Map<String, String>,
    saving: Boolean,
    onSave: (entryId: Int, activityId: Int, beginIso: String, endIso: String?, newColor: String?, description: String, tags: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val act = activities.firstOrNull { it.id == entry.activity }
    val running = entry.end == null

    var begin by remember(entry.id) {
        mutableStateOf(parseKimaiLocal(entry.begin) ?: LocalDateTime.now())
    }
    var end by remember(entry.id) { mutableStateOf(parseKimaiLocal(entry.end)) }
    var description by remember(entry.id) { mutableStateOf(entry.description ?: "") }
    val selectedTags = remember(entry.id) {
        mutableStateListOf<String>().apply {
            addAll(entry.tags?.filter { it.isNotBlank() }.orEmpty())
        }
    }
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
                FieldLabel("Description")
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("What was this?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3,
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("Tags")
                if (allTags.isEmpty()) {
                    Text(
                        "No tags on the server yet.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        allTags.forEach { tag ->
                            val isSel = selectedTags.contains(tag)
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    if (isSel) selectedTags.remove(tag) else selectedTags.add(tag)
                                },
                                label = { Text(tag) },
                            )
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
                    onSave(
                        entry.id, entry.activity, formatKimai(begin), endIso, newColor,
                        description.trim(), selectedTags.joinToString(","),
                    )
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
