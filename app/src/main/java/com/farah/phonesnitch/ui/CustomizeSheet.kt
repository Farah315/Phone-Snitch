package com.farah.phonesnitch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.CustomizeItem
import com.farah.phonesnitch.ui.components.AppIcon

/**
 * Shown on first run and from Settings.
 * Star = pin the app on top of the home page. Category chip = fix the AI's category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeSheet(
    items: List<CustomizeItem>,
    onTogglePin: (packageName: String, pinned: Boolean) -> Unit,
    onCategoryChange: (packageName: String, category: AppCategory?) -> Unit,
    onLimitChange: (packageName: String, minutes: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(items, query) {
        if (query.isBlank()) items else items.filter { it.app.label.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Customize your apps", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Star the apps you want pinned on top. Tap a category to fix it if the AI got it wrong. " +
                            "You can come back to this from Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
            items(filtered, key = { it.app.packageName }) { item ->
                CustomizeRow(item, onTogglePin, onCategoryChange, onLimitChange)
            }
        }
    }
}

@Composable
private fun CustomizeRow(
    item: CustomizeItem,
    onTogglePin: (String, Boolean) -> Unit,
    onCategoryChange: (String, AppCategory?) -> Unit,
    onLimitChange: (String, Int?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pkg = item.app.packageName

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg, 40.dp)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Box {
                AssistChip(
                    onClick = { menuOpen = true },
                    label = { Text(item.category.label + if (item.categoryOverridden) " · you" else "") },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Auto (let the AI decide)") },
                        onClick = {
                            onCategoryChange(pkg, null)
                            menuOpen = false
                        },
                    )
                    AppCategory.entries.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.label) },
                            onClick = {
                                onCategoryChange(pkg, category)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            LimitChip(item.dailyLimitMinutes) { onLimitChange(pkg, it) }
        }

        IconButton(onClick = { onTogglePin(pkg, !item.pinned) }) {
            Icon(
                imageVector = if (item.pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (item.pinned) "Unpin" else "Pin",
                tint = if (item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Daily time limit for one app. Only enforced once Usage Access is granted. */
@Composable
private fun LimitChip(limitMinutes: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(if (limitMinutes == null) "No daily limit" else "Limit: $limitMinutes min/day") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("No limit") },
                onClick = {
                    onChange(null)
                    open = false
                },
            )
            listOf(15, 30, 60, 120).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes min per day") },
                    onClick = {
                        onChange(minutes)
                        open = false
                    },
                )
            }
        }
    }
}
