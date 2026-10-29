package com.farah.phonesnitch.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farah.phonesnitch.domain.DetoxPolicy
import com.farah.phonesnitch.domain.model.AppInfo
import com.farah.phonesnitch.domain.model.DetoxStage
import com.farah.phonesnitch.domain.model.HomeSection
import com.farah.phonesnitch.domain.model.TimeSlot
import com.farah.phonesnitch.ui.components.AppIconItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherRoot(viewModel: LauncherViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val classifying by viewModel.classifying.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var showDashboard by remember { mutableStateOf(false) }
    val timeStats by viewModel.timeStats.collectAsStateWithLifecycle()
    val usageAccess by viewModel.usageAccess.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val grantUsageAccess = remember(context) { { openUsageAccessSettings(context) } }

    LaunchedEffect(showDashboard) {
        if (showDashboard) viewModel.onDashboardOpened()
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showAssistant by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // First run: start with the Customize screen so the user can pick their apps right away.
    var showCustomize by remember { mutableStateOf(viewModel.needsOnboarding) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }

    // Back from "All apps" returns to the smart home page, like a real launcher.
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Quick Assistant button
            ExtendedFloatingActionButton(
                onClick = { showAssistant = true },
                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                text = { Text("Ask AI") },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) { page ->
            if (page == 0) {
                HomePage(
                    state = state,
                    onAppClick = viewModel::onAppClick,
                    onOpenSettings = { showSettings = true },
                    onOpenDashboard = { showDashboard = true },
                    onSeeAll = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            } else {
                AllAppsPage(apps = state.allApps, onAppClick = viewModel::onAppClick)
            }
        }
    }

    if (showAssistant) {
        AgentSheet(
            state = agent,
            models = viewModel.agentModels,
            onSelectModel = viewModel::selectAgentModel,
            onRun = viewModel::runAgent,
            onCompare = viewModel::compareAgentModels,
            onDismiss = {
                showAssistant = false
                viewModel.clearAgent()
            },
        )
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            classifying = classifying,
            onToggleDetox = viewModel::toggleDetox,
            onClassifyNow = viewModel::classifyNow,
            usageAccess = usageAccess,
            onGrantUsageAccess = grantUsageAccess,
            onCustomize = {
                showSettings = false
                showCustomize = true
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showDashboard) {
        DashboardSheet(
            stats = stats,
            timeStats = timeStats,
            usageAccess = usageAccess,
            onGrantUsageAccess = grantUsageAccess,
            onDismiss = { showDashboard = false },
        )
    }

    if (showCustomize) {
        CustomizeSheet(
            items = state.customizeItems,
            onTogglePin = viewModel::setPinned,
            onCategoryChange = viewModel::setCategory,
            onLimitChange = viewModel::setDailyLimit,
            onDismiss = {
                showCustomize = false
                viewModel.markOnboardingDone()
            },
        )
    }
}

@Composable
private fun HomePage(
    state: LauncherUiState,
    onAppClick: (AppInfo) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDashboard: () -> Unit,
    onSeeAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Extra bottom padding keeps the last section clear of the floating button.
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { HomeHeader(state, onOpenSettings, onOpenDashboard) }

        if (state.pinned.isNotEmpty()) {
            item { AppRowSection("Pinned", state.pinned, onAppClick) }
        }

        if (state.sections.isEmpty() && state.pinned.isEmpty()) {
            item {
                Text(
                    text = if (state.loading) "Loading apps…" else "No apps to show yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(state.sections, key = { it.category.key }) { section ->
            CategorySection(section, onAppClick)
        }

        item {
            TextButton(onClick = onSeeAll) { Text("All apps  →") }
        }
    }
}

@Composable
private fun HomeHeader(state: LauncherUiState, onOpenSettings: () -> Unit, onOpenDashboard: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(greeting(state.slot), style = MaterialTheme.typography.headlineMedium)
            Text(
                "Showing what fits your ${state.slot.label.lowercase()} routine",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.detoxStage != DetoxStage.OFF) {
                AssistChip(
                    onClick = onOpenSettings,
                    label = { Text("Detox · day ${state.detoxDay}") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        IconButton(onClick = onOpenDashboard) {
            Icon(Icons.Default.BarChart, contentDescription = "Dashboard")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

private fun greeting(slot: TimeSlot): String = when (slot) {
    TimeSlot.MORNING -> "Good morning"
    TimeSlot.WORK_HOURS -> "Focus time"
    TimeSlot.EVENING -> "Good evening"
    TimeSlot.NIGHT -> "Late night"
}

@Composable
private fun CategorySection(section: HomeSection, onAppClick: (AppInfo) -> Unit) =
    AppRowSection(section.category.label, section.apps, onAppClick)

@Composable
private fun AppRowSection(title: String, apps: List<AppInfo>, onAppClick: (AppInfo) -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(apps, key = { it.packageName }) { app ->
                AppIconItem(app = app, onClick = onAppClick, modifier = Modifier.width(72.dp))
            }
        }
    }
}

@Composable
private fun AllAppsPage(apps: List<AppInfo>, onAppClick: (AppInfo) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.packageName }) { app ->
                AppIconItem(app = app, onClick = onAppClick)
            }
        }
    }
}

/** Opens the system page where the user turns on "Usage access" for this app. */
private fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Open Settings > Apps > Special access > Usage access", Toast.LENGTH_LONG).show()
    }
}

/** Short description for the settings sheet. */
internal fun detoxDescription(stage: DetoxStage): String = DetoxPolicy.describe(stage)
