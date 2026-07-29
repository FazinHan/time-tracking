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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.BatchAction
import com.fizaan.kimaitimer.ToolsState
import com.fizaan.kimaitimer.UNTAGGED
import com.fizaan.kimaitimer.data.Activity
import com.fizaan.kimaitimer.util.entrySeconds
import com.fizaan.kimaitimer.util.formatDuration
import com.fizaan.kimaitimer.util.parseKimaiLocal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Batch edit: narrow the timesheet down with any combination of activity, tag
 * and duration over a date range, then apply one change to everything that
 * matched. Every action is confirmed; deleting is confirmed twice.
 */
@Composable
fun BatchTool(
    state: ToolsState,
    onSetActivity: (Int?) -> Unit,
    onSetTag: (String?) -> Unit,
    onSetMin: (String) -> Unit,
    onSetMax: (String) -> Unit,
    onSetFrom: (LocalDate) -> Unit,
    onSetTo: (LocalDate) -> Unit,
    onSearch: () -> Unit,
    onAsk: (BatchAction, Int?, List<String>, String?) -> Unit,
    onAskActivityName: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val b = state.batch
    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        BatchSectionLabel("Match entries — leave anything blank to ignore it")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityChip(
                activities = state.activities,
                selectedId = b.filterActivityId,
                onSelect = onSetActivity,
            )
            TagChip(allTags = state.allTags, selected = b.filterTag, onSelect = onSetTag)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = b.minMinutes,
                onValueChange = onSetMin,
                label = { Text("Min minutes") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = b.maxMinutes,
                onValueChange = onSetMax,
                label = { Text("Max minutes") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        BatchSectionLabel("Within")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFrom = true }, modifier = Modifier.weight(1f)) {
                Text(b.from.format(dateFmt))
            }
            Text(
                "→",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = { pickTo = true }, modifier = Modifier.weight(1f)) {
                Text(b.to.format(dateFmt))
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onSearch,
            enabled = !b.searching && !b.applying,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (b.searching) "Finding…" else "Find matching entries")
        }

        b.done?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = KimaiGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        b.progress?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                "Applying — $it",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontSize = 14.sp,
            )
        }

        if (b.searched) {
            Spacer(Modifier.height(20.dp))
            MatchSummary(state)
            if (b.matches.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                ActionPanel(
                    state = state,
                    onAsk = onAsk,
                    onAskActivityName = onAskActivityName,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (pickFrom) {
        BatchDateDialog(initial = b.from, onDismiss = { pickFrom = false }, onPick = onSetFrom)
    }
    if (pickTo) {
        BatchDateDialog(initial = b.to, onDismiss = { pickTo = false }, onPick = onSetTo)
    }

    b.pending?.let { action ->
        ConfirmDialog(
            state = state,
            action = action,
            second = b.confirmedOnce,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun MatchSummary(state: ToolsState) {
    val b = state.batch
    val now = System.currentTimeMillis()
    if (b.matches.isEmpty()) {
        Text(
            "Nothing matched those filters.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 14.sp,
        )
        return
    }
    val total = b.matches.sumOf { entrySeconds(it.begin, it.end, it.duration, now) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("d MMM, HH:mm") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Text(
            "${b.matches.size} ${if (b.matches.size == 1) "entry" else "entries"} · " +
                formatDuration(total),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        // A sample, so it's clear what's about to be changed without listing
        // hundreds of rows.
        b.matches.takeLast(6).reversed().forEach { e ->
            val name = state.activities.firstOrNull { it.id == e.activity }?.name
                ?: "#${e.activity}"
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    parseKimaiLocal(e.begin)?.format(dayFmt) ?: "?",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
        }
        if (b.matches.size > 6) {
            Text(
                "…and ${b.matches.size - 6} more",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionPanel(
    state: ToolsState,
    onAsk: (BatchAction, Int?, List<String>, String?) -> Unit,
    onAskActivityName: (String) -> Unit,
) {
    val b = state.batch
    val tags = remember(b.matches) { mutableStateListOf<String>() }
    val busy = b.applying

    // Prefilled with the activity these entries already use, so changing it is
    // literally editing the name.
    val currentName = remember(b.matches, state.activities) {
        val ids = b.matches.map { it.activity }.distinct()
        if (ids.size == 1) state.activities.firstOrNull { it.id == ids.first() }?.name ?: ""
        else ""
    }
    var newName by remember(currentName) { mutableStateOf(currentName) }

    BatchSectionLabel("Apply to all ${b.matches.size}")

    OutlinedTextField(
        value = newName,
        onValueChange = { newName = it },
        label = { Text("Activity name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onAskActivityName(newName) },
        enabled = !busy && newName.isNotBlank() && newName.trim() != currentName,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Change activity")
    }
    Text(
        "A new name renames the activity itself. A name that already exists can " +
            "only take these entries in — you'll be asked first.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 6.dp),
    )

    Spacer(Modifier.height(16.dp))
    BatchSectionLabel("Set tags to")
    if (state.allTags.isEmpty()) {
        Text(
            "No tags on the server yet.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.allTags.forEach { tag ->
                val sel = tags.contains(tag)
                FilterChip(
                    selected = sel,
                    onClick = { if (sel) tags.remove(tag) else tags.add(tag) },
                    label = { Text(tag) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onAsk(BatchAction.SET_TAGS, null, tags.toList(), null) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (tags.isEmpty()) "Clear tags on all" else "Set ${tags.size} tag(s) on all")
        }
    }

    Spacer(Modifier.height(16.dp))
    BatchSectionLabel("Recolour their activities")
    val swatches = state.colorChoices
    if (swatches.isEmpty()) {
        Text(
            "The server didn't return a colour palette.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            swatches.values.forEach { hex ->
                val c = parseHexColor(hex) ?: return@forEach
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(c, CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable(enabled = !busy) {
                            onAsk(BatchAction.SET_COLOR, null, emptyList(), hex)
                        },
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    OutlinedButton(
        onClick = { onAsk(BatchAction.DELETE, null, emptyList(), null) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Delete all ${b.matches.size}", color = KimaiRed)
    }
}

/**
 * One dialog for every action. Deleting comes back a second time with a blunter
 * question, since nothing about it can be undone.
 */
@Composable
private fun ConfirmDialog(
    state: ToolsState,
    action: BatchAction,
    second: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val b = state.batch
    val n = b.matches.size
    val what = "$n ${if (n == 1) "entry" else "entries"}"
    val targetName = state.activities.firstOrNull { it.id == b.pendingActivityId }?.name

    val title: String
    val body: String
    when (action) {
        BatchAction.RENAME_ACTIVITY -> {
            title = "Rename “${targetName ?: "?"}”?"
            body = "The activity itself is renamed to “${b.pendingActivityName}”, so " +
                "every entry under it takes the new name — including any outside " +
                "these $what."
        }
        BatchAction.MOVE_ACTIVITY -> {
            title = "“${b.pendingActivityName}” already exists"
            body = "An activity by that name is already on the server, so nothing can " +
                "be renamed to it. Add these $what to “${b.pendingActivityName}” instead?"
        }
        BatchAction.SET_TAGS -> {
            title = if (b.pendingTags.isEmpty()) "Clear tags on $what?" else "Retag $what?"
            body = if (b.pendingTags.isEmpty()) {
                "Every tag is removed from all $what. Existing tags are not kept."
            } else {
                "All $what will be tagged “${b.pendingTags.joinToString(", ")}”, " +
                    "replacing whatever tags they have now."
            }
        }
        BatchAction.SET_COLOR -> {
            val acts = b.matches.map { it.activity }.distinct().size
            title = "Recolour $acts ${if (acts == 1) "activity" else "activities"}?"
            body = "Colour is a property of the activity, not the entry, so this " +
                "changes how those activities look everywhere in Kimai — not just " +
                "for the $what you matched."
        }
        BatchAction.DELETE -> if (second) {
            title = "Really delete $what?"
            body = "Last chance. $what will be removed from the server permanently. " +
                "There is no undo."
        } else {
            title = "Delete $what?"
            body = "This removes all $what from the server."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            OutlinedButton(onClick = onConfirm) {
                Text(
                    when {
                        action == BatchAction.DELETE && second -> "Delete permanently"
                        action == BatchAction.DELETE -> "Continue"
                        action == BatchAction.MOVE_ACTIVITY -> "Yes, add them"
                        action == BatchAction.RENAME_ACTIVITY -> "Rename"
                        else -> "Apply"
                    },
                    color = if (action == BatchAction.DELETE) KimaiRed
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (action == BatchAction.MOVE_ACTIVITY) "No" else "Cancel")
            }
        },
    )
}

@Composable
private fun ActivityChip(
    activities: List<Activity>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        val name = activities.firstOrNull { it.id == selectedId }?.name
        FilterChip(
            selected = selectedId != null,
            onClick = { menu = true },
            label = { Text(name ?: "Any activity") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Any activity") },
                onClick = { onSelect(null); menu = false },
            )
            activities.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.name) },
                    onClick = { onSelect(a.id); menu = false },
                )
            }
        }
    }
}

@Composable
private fun TagChip(allTags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        val label = when (selected) {
            null -> "Any tag"
            UNTAGGED -> "untagged"
            else -> selected
        }
        FilterChip(
            selected = selected != null,
            onClick = { menu = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Any tag") },
                onClick = { onSelect(null); menu = false },
            )
            allTags.forEach { t ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onSelect(t); menu = false })
            }
            DropdownMenuItem(
                text = { Text("untagged") },
                onClick = { onSelect(UNTAGGED); menu = false },
            )
        }
    }
}

@Composable
private fun BatchSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun BatchDateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) = ToolDateDialog(initial = initial, onDismiss = onDismiss, onPick = onPick)
