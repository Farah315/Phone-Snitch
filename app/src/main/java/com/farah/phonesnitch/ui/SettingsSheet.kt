package com.farah.phonesnitch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: LauncherUiState,
    classifying: Boolean,
    onToggleDetox: (Boolean) -> Unit,
    onClassifyNow: () -> Unit,
    onCustomize: () -> Unit,
    usageAccess: Boolean,
    onGrantUsageAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Social media detox", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (state.detoxEnabled) {
                            "Day ${state.detoxDay}. ${detoxDescription(state.detoxStage)}"
                        } else {
                            "Gradually hides social apps: pushed down, then removed from home, then blocked 2 days a week."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.detoxEnabled, onCheckedChange = onToggleDetox)
            }

            HorizontalDivider()

            Text("AI categorization", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${state.classifiedCount} apps classified. It runs automatically once a day.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onClassifyNow, enabled = !classifying) {
                Text(if (classifying) "Classifying…" else "Classify apps now")
            }

            HorizontalDivider()

            Text("Your apps", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Pin favorites to the top and fix categories the AI got wrong.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onCustomize) { Text("Customize apps") }

            HorizontalDivider()

            Text("Usage access", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (usageAccess) {
                    "Granted. Opens from anywhere are counted, and screen time and daily limits work."
                } else {
                    "Not granted. Only opens from this launcher are counted and daily limits are off."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!usageAccess) {
                OutlinedButton(onClick = onGrantUsageAccess) { Text("Grant usage access") }
            }
        }
    }
}
