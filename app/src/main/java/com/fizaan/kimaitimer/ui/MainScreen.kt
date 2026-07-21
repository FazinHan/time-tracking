package com.fizaan.kimaitimer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.UiState
import com.fizaan.kimaitimer.data.TimesheetActive
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(
    state: UiState,
    onMenu: () -> Unit,
    onStartTap: () -> Unit,
    onStopTap: () -> Unit,
    onPickActivity: (Int) -> Unit,
    onResume: (TimesheetActive) -> Unit,
    onDismissPicker: () -> Unit,
    onOpenCreate: () -> Unit,
    onDismissCreate: () -> Unit,
    onCreateActivity: (String) -> Unit,
    onEditTag: (Int) -> Unit,
    onConfirmTag: (List<String>) -> Unit,
    onDismissTagDialog: () -> Unit,
    onRefresh: () -> Unit,
    onReconfigure: () -> Unit,
    onClearError: () -> Unit,
) {
    val running = state.running != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, "Menu", tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = state.projectName.ifBlank { "Kimai Timer" },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                )
                if (running) {
                    Text(
                        text = "Tracking: ${state.running?.activity?.name ?: "activity"}",
                        color = KimaiRed,
                        fontSize = 13.sp,
                    )
                }
            }
            Row {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = onReconfigure) {
                    Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        // Center button
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (running) {
                ElapsedTimer(beginIso = state.running?.begin, color = KimaiRed)
                Spacer(Modifier.height(24.dp))
            }
            BigButton(
                running = running,
                busy = state.busy,
                onClick = { if (running) onStopTap() else onStartTap() },
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = if (running) "Tap to stop" else "Tap to start",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontSize = 16.sp,
            )
        }

        // Error toast-ish banner
        state.error?.let { err ->
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .background(KimaiRed, CircleShape)
                        .clickable { onClearError() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(err, color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (state.showPickDialog) {
        ActivityPickerDialog(
            state = state,
            onPick = onPickActivity,
            onResume = onResume,
            onEditTag = onEditTag,
            onCreate = onOpenCreate,
            onDismiss = onDismissPicker,
        )
    }
    if (state.showCreateDialog) {
        CreateActivityDialog(
            busy = state.busy,
            onCreate = onCreateActivity,
            onDismiss = onDismissCreate,
        )
    }
    if (state.showTagDialog) {
        TagPickerDialog(
            activityName = state.tagActivityName,
            allTags = state.allTags,
            initiallySelected = state.tagSelected,
            onConfirm = onConfirmTag,
            onDismiss = onDismissTagDialog,
        )
    }
}

@Composable
private fun BigButton(running: Boolean, busy: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (running) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = if (running) 1f else 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring",
    )

    val size = 220.dp
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            // Red pulsating outlined stop button
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(scale)
                    .border(BorderStroke(6.dp, KimaiRed.copy(alpha = ringAlpha)), CircleShape)
                    .background(KimaiRed.copy(alpha = 0.10f), CircleShape)
                    .clickable(enabled = !busy) { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(color = KimaiRed)
                } else {
                    Icon(Icons.Filled.Stop, "Stop", tint = KimaiRed, modifier = Modifier.size(96.dp))
                }
            }
        } else {
            // Solid green play button
            Box(
                modifier = Modifier
                    .size(size)
                    .background(KimaiGreen, CircleShape)
                    .clickable(enabled = !busy) { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Icon(Icons.Filled.PlayArrow, "Start", tint = Color.White, modifier = Modifier.size(110.dp))
                }
            }
        }
    }
}

/** Live-ticking elapsed time (HH:MM:SS) since the running timesheet began. */
@Composable
private fun ElapsedTimer(beginIso: String?, color: Color) {
    val beginMs = remember(beginIso) { parseBeginMillis(beginIso) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(beginIso) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = if (beginMs == null) "--:--:--" else formatElapsed(now - beginMs)
    Text(
        text = text,
        color = color,
        fontSize = 40.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
    )
}

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private val OFFSET_NO_COLON = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")
private val LOCAL_NO_ZONE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** Parse Kimai's begin timestamp (e.g. "2026-07-20T15:36:00+0530") to epoch millis. */
private fun parseBeginMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    try {
        return OffsetDateTime.parse(iso, OFFSET_NO_COLON).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    try {
        return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    return try {
        LocalDateTime.parse(iso, LOCAL_NO_ZONE)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivityPickerDialog(
    state: UiState,
    onPick: (Int) -> Unit,
    onResume: (TimesheetActive) -> Unit,
    onEditTag: (Int) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Recent activities to resume, de-duplicated by activity (keeping most recent).
    val recent = remember(state.recent) {
        state.recent
            .filter { it.activity != null }
            .distinctBy { it.activity?.id }
            .take(5)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start activity") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (recent.isNotEmpty()) {
                    SectionLabel("Recent — tap to resume · long-press to tag")
                    recent.forEach { item ->
                        RecentRow(
                            item = item,
                            onClick = { onResume(item) },
                            onLongClick = { item.activity?.id?.let(onEditTag) },
                        )
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    }
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("All activities — tap to start · long-press to tag")
                }
                if (state.activities.isEmpty()) {
                    Text("No activities yet. Create one below.")
                }
                state.activities.forEach { act ->
                    Text(
                        text = act.name,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPick(act.id) },
                                onLongClick = { onEditTag(act.id) },
                            )
                            .padding(vertical = 14.dp),
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.size(4.dp))
                Text("Create activity")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(item: TimesheetActive, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = item.activity?.name ?: "activity",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val desc = item.description?.takeIf { it.isNotBlank() }
        if (desc != null) {
            Text(
                text = desc,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        val tags = item.tags?.filter { it.isNotBlank() }.orEmpty()
        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString(" · "),
                fontSize = 12.sp,
                color = KimaiGreen,
            )
        }
    }
}

/**
 * Choose which tag(s) ride with an activity. Backed by the server's existing
 * tags (Kimai silently drops unknown tags, so free text is not offered). The
 * choice is remembered per-activity on the device.
 */
@Composable
private fun TagPickerDialog(
    activityName: String,
    allTags: List<String>,
    initiallySelected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(initiallySelected) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag “$activityName”") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (allTags.isEmpty()) {
                    Text("No tags found on the server. Add tags in Kimai first.")
                } else {
                    Text(
                        text = "This choice is saved and reused every time you start this activity.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    allTags.forEach { tag ->
                        val isSel = selected.contains(tag)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSel) selected.remove(tag) else selected.add(tag)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isSel) Icons.Filled.CheckBox
                                else Icons.Filled.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (isSel) KimaiGreen
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(tag, fontSize = 17.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = { onConfirm(selected.toList()) }) {
                Text(if (selected.isEmpty()) "Save (no tag)" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CreateActivityDialog(
    busy: Boolean,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New activity") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Activity name") },
                singleLine = true,
            )
        },
        confirmButton = {
            OutlinedButton(enabled = !busy && name.isNotBlank(), onClick = { onCreate(name) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
