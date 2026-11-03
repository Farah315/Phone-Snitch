package com.farah.phonesnitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farah.phonesnitch.domain.model.AppOpenStat
import com.farah.phonesnitch.domain.model.DashboardStats
import com.farah.phonesnitch.domain.model.LimitStat
import com.farah.phonesnitch.domain.model.RecentLaunch
import com.farah.phonesnitch.domain.model.TimeStats
import com.farah.phonesnitch.ui.components.AppIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Usage dashboard.
 * Without Usage Access it only knows about opens made from this launcher.
 * With it: opens from anywhere, screen time per app, and daily limits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSheet(
    stats: DashboardStats?,
    timeStats: TimeStats,
    usageAccess: Boolean,
    onGrantUsageAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val hasOpens = stats != null && (stats.opensLast7Days > 0 || stats.recent.isNotEmpty())

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dashboard", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (usageAccess) "Opens from anywhere on your phone, and time spent."
                        else "Counting only apps you open from this launcher.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!usageAccess) {
                item { AccessBanner(onGrantUsageAccess) }
            }

            if (stats == null || !hasOpens) {
                item {
                    Text(
                        "No opens recorded yet. Open an app and it will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatCard("Opens today", stats.opensToday.toString(), Modifier.weight(1f))
                        StatCard("Last 7 days", stats.opensLast7Days.toString(), Modifier.weight(1f))
                        StatCard("Apps today", stats.distinctAppsToday.toString(), Modifier.weight(1f))
                    }
                }
            }

            if (usageAccess) {
                item {
                    StatCard("Screen time today", formatMinutes(timeStats.totalMinutesToday), Modifier.fillMaxWidth())
                }
                if (timeStats.limits.isNotEmpty()) {
                    item { SectionTitle("Daily limits") }
                    items(timeStats.limits, key = { "limit-" + it.packageName }) { LimitRow(it) }
                }
                if (timeStats.timeToday.isNotEmpty()) {
                    item {
                        Column {
                            SectionTitle("Time per app today")
                            val max = timeStats.timeToday.maxOf { it.minutes }
                            timeStats.timeToday.forEach { BarRow(it.label, it.minutes, max, formatMinutes(it.minutes)) }
                        }
                    }
                }
            }

            if (stats != null && hasOpens) {
                item { SectionTitle("Opens per day") }
                item { DayChart(stats) }

                item {
                    Column {
                        SectionTitle("By time of day")
                        val max = stats.perSlot.values.maxOrNull() ?: 0
                        stats.perSlot.forEach { (slot, count) -> BarRow(slot.label, count, max, count.toString()) }
                    }
                }

                if (stats.perCategory.isNotEmpty()) {
                    item {
                        Column {
                            SectionTitle("By category")
                            val max = stats.perCategory.maxOf { it.second }
                            stats.perCategory.take(6).forEach { (category, count) ->
                                BarRow(category.label, count, max, count.toString())
                            }
                        }
                    }
                }

                if (stats.topApps.isNotEmpty()) {
                    item { SectionTitle("Most opened (7 days)") }
                    items(stats.topApps, key = { "top-" + it.packageName }) { TopAppRow(it) }
                }

                if (stats.recent.isNotEmpty()) {
                    item { SectionTitle("Recent opens") }
                    items(stats.recent, key = { "recent-" + it.timestamp + it.packageName }) { RecentRow(it) }
                }
            }
        }
    }
}

@Composable
private fun AccessBanner(onGrant: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Allow Usage access", style = MaterialTheme.typography.titleMedium)
            Text(
                "So the launcher also counts apps you open from notifications or anywhere else, " +
                    "shows time spent per app, and can enforce daily limits. " +
                    "Android opens a settings page: find Phone Snitch and turn it on.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onGrant) { Text("Open settings") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DayChart(stats: DashboardStats) {
    val max = (stats.perDay.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val today = LocalDate.now()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        stats.perDay.forEach { day ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(day.count.toString(), style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .fillMaxWidth()
                        .height(maxOf(4f, 96f * day.count / max).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (day.date == today) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        ),
                )
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun BarRow(label: String, value: Int, max: Int, valueText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(
            progress = { if (max == 0) 0f else value.toFloat() / max },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Text(
            valueText,
            modifier = Modifier
                .width(56.dp)
                .padding(start = 8.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LimitRow(item: LimitStat) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AppIcon(item.packageName, 32.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                item.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${item.usedMinutes} / ${item.limitMinutes} min" + if (item.reached) " · reached" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.reached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        LinearProgressIndicator(
            progress = { (item.usedMinutes.toFloat() / item.limitMinutes).coerceIn(0f, 1f) },
            color = if (item.reached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun TopAppRow(item: AppOpenStat) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIcon(item.packageName, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text("Last: " + formatTime(item.lastOpened), style = MaterialTheme.typography.bodySmall)
        }
        Text("${item.count}×", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RecentRow(item: RecentLaunch) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIcon(item.packageName, 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            item.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(formatTime(item.timestamp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

private fun formatTime(timestamp: Long): String {
    val time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    val today = LocalDate.now()
    return when (time.toLocalDate()) {
        today -> "Today $clock"
        today.minusDays(1) -> "Yesterday $clock"
        else -> time.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " $clock"
    }
}
