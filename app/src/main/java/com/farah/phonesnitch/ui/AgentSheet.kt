package com.farah.phonesnitch.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.farah.phonesnitch.agent.AgentModel
import com.farah.phonesnitch.agent.AgentResult
import com.farah.phonesnitch.agent.AgentStep
import com.farah.phonesnitch.agent.AgentUiState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val suggestions = listOf(
    "Open WhatsApp",
    "How many times did I open Instagram today?",
    "Limit TikTok to 20 minutes",
    "Turn on social media detox",
    "What did I use the most today?",
)

/**
 * Quick Assistant as an agent: the model reads the request and decides which of our tools to call
 * (open an app, read usage, set a limit...). The steps it took are shown, so you can see the tool calling.
 * "Compare models" runs the same request on every model as a dry run and shows the tools each one chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(
    state: AgentUiState,
    models: List<AgentModel>,
    onSelectModel: (AgentModel) -> Unit,
    onRun: (String) -> Unit,
    onCompare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }

    // RecognizerIntent opens the system speech UI, so no RECORD_AUDIO permission is needed.
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = spoken
            onRun(spoken)
        }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell me what to do")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Speech recognition isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Assistant", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Ask or tell it what to do. The model chooses which tools to call.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Box {
                    AssistChip(
                        onClick = { modelMenu = true },
                        label = { Text("Model: ${state.selected.label}") },
                    )
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.label) },
                                onClick = {
                                    onSelectModel(model)
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Type or speak a request") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onRun(text) }),
                    trailingIcon = {
                        IconButton(onClick = ::startVoice) {
                            Icon(Icons.Default.Mic, contentDescription = "Speak")
                        }
                    },
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { suggestion ->
                        AssistChip(
                            onClick = {
                                // Tapping a suggestion runs it right away, no need to press Run.
                                if (!state.loading) {
                                    text = suggestion
                                    onRun(suggestion)
                                }
                            },
                            label = { Text(suggestion) },
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRun(text) }, enabled = text.isNotBlank() && !state.loading) {
                        Text("Run")
                    }
                    OutlinedButton(onClick = { onCompare(text) }, enabled = text.isNotBlank() && !state.loading) {
                        Text("Compare models")
                    }
                }
            }

            if (state.loading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            state.result?.let { result ->
                item { ResultCard(result) }
            }

            if (state.comparison.isNotEmpty()) {
                item {
                    Text(
                        "Dry run: same request on every model. Nothing was opened or changed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item { ComparisonTable(state.comparison) }
            }
        }
    }
}

/** Turns a raw tool call into a sentence a normal user understands. */
private fun friendly(step: AgentStep): String {
    val args = runCatching { Json.parseToJsonElement(step.args).jsonObject }.getOrNull()
    fun arg(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val app = arg("app_name").ifBlank { arg("query") }
    return when (step.tool) {
        "find_apps" -> "Searched your apps for \"$app\""
        "open_app" -> "Opened $app"
        "get_usage_stats" -> "Checked your usage (${arg("period").ifBlank { "today" }})"
        "get_app_usage" -> "Checked $app usage"
        "set_daily_limit" -> "Set a daily limit for $app"
        "set_social_detox" -> "Changed the social detox"
        "pin_app" -> "Updated pin for $app"
        "set_app_category" -> "Changed category of $app"
        else -> step.tool
    }
}

private fun seconds(ms: Long) = "%.1fs".format(ms / 1000.0)

/** Normal result: the answer first, what the model did as small chips, raw data only on demand. */
@Composable
private fun ResultCard(result: AgentResult) {
    var details by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (result.answer.isNotBlank()) {
                SelectionContainer { Text(result.answer, style = MaterialTheme.typography.bodyLarge) }
            }
            if (result.error != null) {
                Text(result.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            result.steps.forEach { step ->
                Text(
                    (if (step.ok) "✓ " else "✗ ") + friendly(step),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "${result.model.label} · ${seconds(result.latencyMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.steps.isNotEmpty()) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
            }
            if (details) RawSteps(result.steps)
        }
    }
}

/** Compare view: one table row per model. Tap a row for its answer and raw tool calls. */
@Composable
private fun ComparisonTable(results: List<AgentResult>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("Model", Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium)
                Text("Tool it chose", Modifier.weight(1.5f), style = MaterialTheme.typography.labelMedium)
                Text("Time", style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
            results.forEach { result ->
                var open by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = !open },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(result.model.label, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium)
                        val chosen = when {
                            result.error != null -> "✗ " + shortError(result.error)
                            result.steps.isEmpty() -> "none (answered directly)"
                            else -> result.steps.joinToString(", ") { it.tool }
                        }
                        Text(
                            chosen,
                            Modifier.weight(1.5f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (result.error == null) FontFamily.Monospace else FontFamily.Default,
                            color = if (result.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(seconds(result.latencyMs), style = MaterialTheme.typography.bodySmall)
                    }
                    if (open) {
                        if (result.answer.isNotBlank()) Text(result.answer, style = MaterialTheme.typography.bodyMedium)
                        if (result.error != null) {
                            Text(result.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        RawSteps(result.steps)
                    }
                }
                HorizontalDivider()
            }
            Text(
                "Tap a row for the answer and the raw tool calls.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun shortError(error: String): String = when {
    error.contains("timeout", ignoreCase = true) || error.contains("timed out", ignoreCase = true) -> "timed out"
    error.startsWith("HTTP ") -> error.substringBefore(" from")
    else -> "failed"
}

@Composable
private fun RawSteps(steps: List<AgentStep>) {
    steps.forEach { step ->
        Column {
            Text(
                step.tool + "(" + step.args + ")",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "→ " + step.result,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}