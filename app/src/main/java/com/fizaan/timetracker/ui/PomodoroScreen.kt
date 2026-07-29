package com.fizaan.timetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.PomodoroState
import com.fizaan.timetracker.pomodoro.Phase
import com.fizaan.timetracker.pomodoro.PomodoroAlert
import com.fizaan.timetracker.pomodoro.PomodoroSettings
import com.fizaan.timetracker.pomodoro.formatRemaining
import com.fizaan.timetracker.pomodoro.isBreak
import com.fizaan.timetracker.pomodoro.phaseAt
import kotlinx.coroutines.delay

/** The one colour a work period is allowed: a flat grey on the same black. */
private val PomodoroGrey = Color(0xFF8A8A8A)

/**
 * The pomodoro.
 *
 * Idle it is the main screen's play button. Running it strips itself down to a
 * countdown and a way out — no menu, no settings — because a work period should
 * offer nothing to look at. Breaks get the familiar red pulse back.
 *
 * The whole session is a single Kimai entry; the work/break structure survives
 * only in the description written when it stops.
 */
@Composable
fun PomodoroScreen(
    state: PomodoroState,
    onMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveSettings: (PomodoroSettings) -> Unit,
    onDismissSettings: () -> Unit,
    onStartTap: () -> Unit,
    onPick: (Int) -> Unit,
    onDismissPicker: () -> Unit,
    onStop: () -> Unit,
    onSkip: () -> Unit,
    onPhaseStarted: (Phase) -> Unit,
    onDismissAlert: () -> Unit,
    onClearError: () -> Unit,
) {
    // One tick per second drives both the countdown and boundary detection.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) {
        while (state.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val slot = remember(now, state.timelineOrigin, state.sessionSettings) {
        if (state.running) phaseAt(state.timelineOrigin, now, state.sessionSettings) else null
    }
    // A phase that turned over while this screen was watching is announced here;
    // one that turned over while the app was away was announced by the alarm, so
    // only a boundary in the last minute is worth interrupting for. A skip is
    // excluded: it moves the schedule deliberately, and the user is right here.
    var lastIndex by remember(state.startMs) { mutableIntStateOf(slot?.index ?: -1) }
    var lastSkew by remember(state.startMs) { mutableLongStateOf(state.skew) }
    LaunchedEffect(slot?.index) {
        val s = slot ?: return@LaunchedEffect
        if (s.index != lastIndex) {
            val skipped = state.skew != lastSkew
            lastIndex = s.index
            lastSkew = state.skew
            if (!skipped && now - s.startMs < 60_000) onPhaseStarted(s.kind)
        }
    }

    val onBreak = slot?.kind?.isBreak == true
    val accent = if (onBreak) StopRed else PomodoroGrey

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        // While a session runs the only thing up here is the way out of the
        // current period — not the drawer, not the settings — by design.
        if (slot != null) {
            SkipPill(
                label = if (slot.kind.isBreak) "Skip to work" else "Skip to break",
                accent = accent,
                enabled = !state.busy,
                onClick = onSkip,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (!state.running) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, "Menu", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text(
                    text = "Pomodoro",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Tune, "Pomodoro settings",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (slot != null) {
                Text(
                    text = state.activityName,
                    color = PomodoroGrey,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = phaseLabel(slot.kind, slot.ordinal),
                    color = PomodoroGrey,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = formatRemaining(slot.endMs - now),
                    color = accent,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(24.dp))
            }
            BigButton(
                running = state.running,
                busy = state.busy,
                onClick = { if (state.running) onStop() else onStartTap() },
                accent = accent,
                pulse = onBreak,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = if (state.running) "Tap to stop" else "Tap to start",
                color = if (state.running) PomodoroGrey
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontSize = 16.sp,
            )
            if (!state.running) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = with(state.settings) {
                        "${workMinutes}m work · ${breakMinutes}m break · " +
                            "${longBreakMinutes}m long break after $breaksBeforeLong"
                    },
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
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
                Text(err, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }

    if (state.showPicker) {
        ProductivePickerDialog(
            state = state,
            onPick = onPick,
            onDismiss = onDismissPicker,
        )
    }
    if (state.showSettings) {
        PomodoroSettingsDialog(
            settings = state.settings,
            onSave = onSaveSettings,
            onDismiss = onDismissSettings,
        )
    }
    state.alert?.let { kind ->
        PhaseAlert(kind = kind, settings = state.sessionSettings, onDismiss = onDismissAlert)
    }
}

private fun phaseLabel(kind: Phase, ordinal: Int): String = when (kind) {
    Phase.WORK -> "Work period $ordinal"
    Phase.BREAK -> "Break $ordinal"
    Phase.LONG_BREAK -> "Long break"
}

/**
 * What the app puts in front of you when a period turns over — full screen, on
 * top of everything, with one thing to do.
 */
@Composable
private fun PhaseAlert(kind: Phase, settings: PomodoroSettings, onDismiss: () -> Unit) {
    val minutes = when (kind) {
        Phase.WORK -> settings.workMinutes
        Phase.BREAK -> settings.breakMinutes
        Phase.LONG_BREAK -> settings.longBreakMinutes
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = PomodoroAlert.title(kind),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (minutes == 1) "1 minute" else "$minutes minutes",
                color = PomodoroGrey,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(36.dp))
            OutlinedButton(onClick = onDismiss) { Text("Got it") }
        }
    }
}

/** Only activities tagged "productive" may be spent as a pomodoro. */
@Composable
private fun ProductivePickerDialog(
    state: PomodoroState,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a pomodoro") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (state.activities.isEmpty()) {
                    Text(
                        "No activity is tagged “productive” yet. Tag one from the " +
                            "timer screen (long-press an activity) and it will show up here.",
                    )
                }
                state.activities.forEach { act ->
                    Text(
                        text = act.name,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(act.id) }
                            .padding(vertical = 14.dp),
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The four numbers the whole schedule is built from. */
@Composable
private fun PomodoroSettingsDialog(
    settings: PomodoroSettings,
    onSave: (PomodoroSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var work by remember { mutableIntStateOf(settings.workMinutes) }
    var brk by remember { mutableIntStateOf(settings.breakMinutes) }
    var long by remember { mutableIntStateOf(settings.longBreakMinutes) }
    var cycle by remember { mutableIntStateOf(settings.breaksBeforeLong) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pomodoro lengths") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Stepper("Work", "$work min", { work = downBy5(work) }) { work = upBy5(work) }
                Stepper("Break", "$brk min", { brk = (brk - 1).coerceAtLeast(1) }) {
                    brk = (brk + 1).coerceAtMost(120)
                }
                Stepper("Long break", "$long min", { long = downBy5(long) }) { long = upBy5(long) }
                Stepper(
                    "Long break after", if (cycle == 1) "1 break" else "$cycle breaks",
                    { cycle = (cycle - 1).coerceAtLeast(1) },
                ) { cycle = (cycle + 1).coerceAtMost(12) }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "New lengths start with the next session — one already " +
                        "running keeps the lengths it began with.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = { onSave(PomodoroSettings(work, brk, long, cycle)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// The minute steppers move in fives but snap to the grid, so a value knocked
// off it (1, the floor) can still climb back to 5, 10, 25 … rather than 6, 11.
private fun upBy5(v: Int) = (((v / 5) + 1) * 5).coerceAtMost(180)
private fun downBy5(v: Int) = (((v - 1) / 5) * 5).coerceAtLeast(1)

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) {
            Icon(Icons.Filled.Remove, "Less $label", tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = value,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(76.dp),
        )
        IconButton(onClick = onPlus) {
            Icon(Icons.Filled.Add, "More $label", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * The only control a running session offers besides stopping: end this period
 * now and begin the next. Outlined in whatever colour the phase is already
 * using, so a work period stays the single flat grey it is meant to be.
 */
@Composable
private fun SkipPill(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) accent else accent.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, tint, CircleShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = tint, fontSize = 14.sp)
    }
}
