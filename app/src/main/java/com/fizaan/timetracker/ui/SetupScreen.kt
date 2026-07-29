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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.SetupState
import kotlin.math.roundToInt

@Composable
fun SetupScreen(
    state: SetupState,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onUseLegacy: (Boolean) -> Unit,
    onLegacyUser: (String) -> Unit,
    onTest: () -> Unit,
    onSelectCustomer: (Int) -> Unit,
    onSelectProject: (Int) -> Unit,
    onFinish: () -> Unit,
    onServerless: (Boolean) -> Unit,
    onFinishLocal: () -> Unit,
    accent: Int,
    onAccent: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Time Tracker setup",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
        )

        if (state.step == 0) {
            Text(
                if (state.serverless) "Keep everything on this device."
                else "Connect to your Kimai server.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.serverless, onCheckedChange = onServerless)
                Text(
                    "  Do not use server",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (state.serverless) {
                LocalModeNotes(hadServer = state.hadServer)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = onFinishLocal,
                    enabled = !state.testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.height(20.dp),
                        )
                    } else {
                        Text("Use this device only")
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = onUrl,
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.0.110:8000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.token,
                    onValueChange = onToken,
                    label = { Text(if (state.useLegacy) "API password" else "API token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.useLegacy, onCheckedChange = onUseLegacy)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "  Legacy auth (older Kimai)",
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (state.useLegacy) {
                    OutlinedTextField(
                        value = state.legacyUser,
                        onValueChange = onLegacyUser,
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = onTest,
                    enabled = !state.testing && state.baseUrl.isNotBlank() &&
                        state.token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.height(20.dp),
                        )
                    } else {
                        Text("Connect")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            ThemePicker(accent = accent, onAccent = onAccent)
        } else {
            Text(
                "Choose your customer and project (only needed once).",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Text("Customer", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
            state.customers.forEach { c ->
                SelectableRow(
                    label = c.name,
                    selected = c.id == state.selectedCustomerId,
                    onClick = { onSelectCustomer(c.id) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Project", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
            val projects = state.projects.filter {
                it.customer == null || it.customer == state.selectedCustomerId
            }
            projects.forEach { p ->
                SelectableRow(
                    label = p.name,
                    selected = p.id == state.selectedProjectId,
                    onClick = { onSelectProject(p.id) },
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onFinish,
                enabled = state.selectedProjectId != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & continue")
            }
        }
    }
}

/**
 * What running without a server actually means. Stated before the choice is
 * made, because two of these are one-way: the data has nowhere else to exist,
 * and it never joins a server added later.
 */
@Composable
private fun LocalModeNotes(hadServer: Boolean) {
    val notes = listOfNotNull(
        if (hadServer) {
            "The app stops contacting your server. It carries on the way it does " +
                "when the server can't be reached — what it already has stays " +
                "visible — except it no longer even tries."
        } else null,
        "Everything you record is kept in this app's storage on this phone. " +
            "It is not uploaded and not backed up anywhere: clearing the app's " +
            "data, or losing the phone, takes the history with it.",
        "There is no size limit — the history keeps growing for as long as you keep it.",
        "Setting up a server later starts the app over on the server's data. " +
            "Nothing local is ever pushed to it; local data stays on the device, " +
            "out of the way, unless you come back to this mode.",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        notes.forEach { note ->
            Row {
                Text("•  ", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(
                    note,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * The accent picker: three presets, then any colour at all through the RGB
 * sliders. Whatever is chosen becomes every accented element in the app, so a
 * colour that would sink into the near-black background is refused rather than
 * quietly applied — see [accentUsable].
 */
@Composable
private fun ThemePicker(accent: Int, onAccent: (Int) -> Unit) {
    Text("Theme", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
    Text(
        "One colour carries the whole app. The stop button stays red whatever you pick.",
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        fontSize = 13.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Accent.entries.forEach { preset ->
            Swatch(
                color = preset.color,
                label = preset.label,
                selected = preset.color.toArgb() == accent,
                onClick = { onAccent(preset.color.toArgb()) },
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        "Custom",
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 16.sp,
    )

    // Seeded from whatever is in force, so the sliders start where the app is
    // and a preset tap moves them with it.
    var red by remember(accent) { mutableIntStateOf((accent shr 16) and 0xFF) }
    var green by remember(accent) { mutableIntStateOf((accent shr 8) and 0xFF) }
    var blue by remember(accent) { mutableIntStateOf(accent and 0xFF) }

    val candidate = Color(red, green, blue)
    val usable = accentUsable(candidate)

    ChannelSlider("R", red, Color(0xFFE05252)) { red = it }
    ChannelSlider("G", green, Color(0xFF52C46A)) { green = it }
    ChannelSlider("B", blue, Color(0xFF5B8FE0)) { blue = it }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(candidate, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Column {
            Text(
                String.format("#%02X%02X%02X", red, green, blue),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                String.format("%.1f:1 against the background", accentContrast(candidate)),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }

    if (!usable) {
        Text(
            "Too close to the background to read. Needs at least " +
                "${MinAccentContrast.toInt()}:1 — try a brighter colour.",
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
        )
    }
    Button(
        onClick = { onAccent(candidate.toArgb()) },
        enabled = usable && candidate.toArgb() != accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Use this colour")
    }
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .background(color, CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .clickable { onClick() },
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(
                alpha = if (selected) 1f else 0.7f,
            ),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, tint: Color, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = tint,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(20.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.roundToInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            value.toString().padStart(3),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(34.dp),
        )
    }
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
    }
}
