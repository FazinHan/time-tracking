package com.fizaan.kimaitimer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.kimaitimer.SetupState

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
            "Kimai Timer setup",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
        )

        if (state.step == 0) {
            Text(
                "Connect to your Kimai server.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
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
                enabled = !state.testing && state.baseUrl.isNotBlank() && state.token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.height(20.dp))
                } else {
                    Text("Connect")
                }
            }
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
