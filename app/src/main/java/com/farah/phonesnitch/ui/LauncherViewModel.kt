package com.farah.phonesnitch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.farah.phonesnitch.AppContainer
import com.farah.phonesnitch.agent.AgentModel
import com.farah.phonesnitch.agent.AgentUiState
import com.farah.phonesnitch.domain.AppRules
import com.farah.phonesnitch.domain.DetoxPolicy
import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.AppInfo
import com.farah.phonesnitch.domain.model.CustomizeItem
import com.farah.phonesnitch.domain.model.DashboardStats
import com.farah.phonesnitch.domain.model.TimeStats
import kotlinx.coroutines.flow.map
import com.farah.phonesnitch.domain.model.DetoxStage
import com.farah.phonesnitch.domain.model.HomeSection
import com.farah.phonesnitch.domain.model.TimeSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

data class LauncherUiState(
    val slot: TimeSlot = TimeSlot.fromHour(LocalDateTime.now().hour),
    val sections: List<HomeSection> = emptyList(),
    val allApps: List<AppInfo> = emptyList(),
    val pinned: List<AppInfo> = emptyList(),
    val customizeItems: List<CustomizeItem> = emptyList(),
    val blockedPackages: Set<String> = emptySet(),
    val detoxEnabled: Boolean = false,
    val detoxStage: DetoxStage = DetoxStage.OFF,
    val detoxDay: Int = 0,
    val classifiedCount: Int = 0,
    val loading: Boolean = true,
)

data class AssistantState(
    val loading: Boolean = false,
    val answer: String? = null,
    val error: String? = null,
)

class LauncherViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.repository
    private val settings = container.settings
    private val usageStats = container.usageStats

    private val installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val appsLoaded = MutableStateFlow(false)

    /** Re-evaluates the time slot (and detox day) every minute. */
    private val ticker = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(60_000)
        }
    }

    /** AI categories + the user's own choices (pins and category overrides). */
    private val classificationsAndPrefs = combine(
        repository.observeClassifications(),
        repository.observePreferences(),
    ) { classifications, prefs -> classifications to prefs }

    private val detoxSettings = combine(settings.detoxEnabled, settings.detoxStartEpochDay) { enabled, start ->
        enabled to start
    }

    val uiState: StateFlow<LauncherUiState> = combine(
        installedApps,
        classificationsAndPrefs,
        repository.observeUsage(),
        detoxSettings,
        ticker,
    ) { apps, (aiClassifications, prefs), usage, detox, now ->
        // The user's category choices always win over the AI's.
        val classifications = AppRules.applyOverrides(aiClassifications, prefs)
        val (enabled, startDay) = detox
        val today = now.toLocalDate()
        val slot = TimeSlot.fromHour(now.hour)
        val startDate = startDay?.let { LocalDate.ofEpochDay(it) }
        val stage = DetoxPolicy.stage(enabled, startDate, today)

        // On blocked days social apps disappear from every screen.
        val blocked = if (DetoxPolicy.isBlockedToday(stage, today)) {
            apps.filter { AppRules.isSocial(it.packageName, classifications[it.packageName]) }
                .map { it.packageName }
                .toSet()
        } else {
            emptySet()
        }
        val visibleApps = apps.filter { it.packageName !in blocked }

        // Pinned apps go on top, except social apps while a detox is running: detox beats pins.
        val pinnedApps = visibleApps.filter { app ->
            prefs[app.packageName]?.pinned == true &&
                !(stage != DetoxStage.OFF && AppRules.isSocial(app.packageName, classifications[app.packageName]))
        }
        val pinnedPackages = pinnedApps.map { it.packageName }.toSet()

        LauncherUiState(
            slot = slot,
            sections = container.buildHomeSections(
                visibleApps.filter { it.packageName !in pinnedPackages },
                classifications, usage, slot, stage,
            ),
            allApps = visibleApps,
            pinned = pinnedApps,
            customizeItems = apps.map { app ->
                CustomizeItem(
                    app = app,
                    category = AppRules.categoryOf(app.packageName, classifications[app.packageName]),
                    categoryOverridden = prefs[app.packageName]?.categoryOverride != null,
                    pinned = prefs[app.packageName]?.pinned == true,
                    dailyLimitMinutes = prefs[app.packageName]?.dailyLimitMinutes,
                )
            },
            blockedPackages = blocked,
            detoxEnabled = enabled,
            detoxStage = stage,
            detoxDay = if (startDate != null) ChronoUnit.DAYS.between(startDate, today).toInt() + 1 else 0,
            classifiedCount = classifications.size,
            loading = !appsLoaded.value,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherUiState())

    /** Numbers for the Dashboard: opens today / last 7 days, per slot, per category, top apps, recent log. */
    val stats: StateFlow<DashboardStats?> = combine(
        repository.observeLaunches(),
        installedApps,
        classificationsAndPrefs,
        ticker,
    ) { launches, apps, (aiClassifications, prefs), now ->
        container.buildDashboardStats(
            launches = launches,
            apps = apps,
            classifications = AppRules.applyOverrides(aiClassifications, prefs),
            today = now.toLocalDate(),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Usage access: opens from anywhere, screen time, daily limits ----

    private val _usageAccess = MutableStateFlow(usageStats.hasAccess())
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    /** Bumped to force a fresh read of today's screen time (e.g. when coming back to the launcher). */
    private val timeRefresh = MutableStateFlow(0)

    private val todayMillis = combine(_usageAccess, timeRefresh, ticker) { granted, _, _ -> granted }
        .map { granted ->
            if (granted) {
                runCatching { usageStats.todayForegroundMillis() }.getOrDefault(emptyMap<String, Long>())
            } else {
                emptyMap<String, Long>()
            }
        }
        .flowOn(Dispatchers.IO)

    val timeStats: StateFlow<TimeStats> = combine(
        todayMillis,
        installedApps,
        repository.observePreferences(),
    ) { millis, apps, prefs ->
        container.buildTimeStats(millis, apps, prefs)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeStats.EMPTY)

    private suspend fun syncUsage() {
        val granted = usageStats.hasAccess()
        _usageAccess.value = granted
        if (granted) {
            try {
                usageStats.importOpens()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit("Couldn't read usage stats")
            }
        }
        timeRefresh.value += 1
    }

    fun onDashboardOpened() {
        viewModelScope.launch { syncUsage() }
    }

    fun setDailyLimit(packageName: String, minutes: Int?) {
        viewModelScope.launch { repository.setDailyLimit(packageName, minutes) }
    }

    private val _assistant = MutableStateFlow(AssistantState())
    val assistant: StateFlow<AssistantState> = _assistant.asStateFlow()

    private val _classifying = MutableStateFlow(false)
    val classifying: StateFlow<Boolean> = _classifying.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** Called from onStart, so newly installed/removed apps show up when you come back home. */
    fun refreshApps() {
        viewModelScope.launch {
            installedApps.value = repository.loadInstalledApps()
            appsLoaded.value = true
            syncUsage()
        }
    }

    fun onAppClick(app: AppInfo) {
        if (app.packageName in uiState.value.blockedPackages) {
            _events.tryEmit("${app.label} is blocked today by Social Media Detox")
            return
        }
        if (app.packageName in timeStats.value.reachedPackages) {
            _events.tryEmit("Daily limit reached for ${app.label}")
            return
        }
        if (!container.appLauncher.launch(app.packageName)) {
            _events.tryEmit("Can't open ${app.label}")
            return
        }
        // Without Usage Access this row is the only record of the open (it feeds the ranking and the dashboard).
        // With access, Android's usage events already contain this open, so recording it here would count it twice.
        if (!_usageAccess.value) {
            viewModelScope.launch { repository.recordLaunch(app.packageName, System.currentTimeMillis()) }
        }
    }

    val needsOnboarding: Boolean get() = !settings.onboardingDone

    fun markOnboardingDone() {
        settings.onboardingDone = true
    }

    fun setPinned(packageName: String, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(packageName, pinned) }
    }

    /** category == null means "let the AI decide again". */
    fun setCategory(packageName: String, category: AppCategory?) {
        viewModelScope.launch { repository.setCategoryOverride(packageName, category) }
    }

    fun toggleDetox(enabled: Boolean) {
        settings.setDetoxEnabled(enabled, LocalDate.now().toEpochDay())
    }

    fun classifyNow() {
        if (_classifying.value) return
        viewModelScope.launch {
            _classifying.value = true
            try {
                container.classifyApps(force = true)
                _events.tryEmit("Apps classified")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(e.message ?: "Classification failed")
            } finally {
                _classifying.value = false
            }
        }
    }

    fun ask(question: String) {
        if (question.isBlank() || _assistant.value.loading) return
        _assistant.value = AssistantState(loading = true)
        viewModelScope.launch {
            _assistant.value = try {
                AssistantState(answer = container.askAssistant(question))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AssistantState(error = e.message ?: "Request failed")
            }
        }
    }

    fun clearAssistant() {
        _assistant.value = AssistantState()
    }

    // ---- Agent (tool calling) ----

    val agentModels: List<AgentModel> = container.agentModels

    private val _agent = MutableStateFlow(AgentUiState(selected = agentModels.first()))
    val agent: StateFlow<AgentUiState> = _agent.asStateFlow()
    private var agentJob: Job? = null

    fun selectAgentModel(model: AgentModel) {
        _agent.update { it.copy(selected = model) }
    }

    /** Runs the agent for real: the model may open apps and change settings through the tools. */
    fun runAgent(message: String) {
        if (message.isBlank() || _agent.value.loading) return
        val model = _agent.value.selected
        _agent.update { it.copy(loading = true, result = null, comparison = emptyList()) }
        agentJob = viewModelScope.launch {
            val result = container.agent.run(model, message.trim(), dryRun = false)
            _agent.update { it.copy(loading = false, result = result) }
        }
    }

    /** Same message on every model, as a dry run (nothing is opened or changed). */
    fun compareAgentModels(message: String) {
        if (message.isBlank() || _agent.value.loading) return
        _agent.update { it.copy(loading = true, result = null, comparison = emptyList()) }
        agentJob = viewModelScope.launch {
            val results = container.agent.compare(agentModels, message.trim())
            _agent.update { it.copy(loading = false, comparison = results) }
        }
    }

    fun clearAgent() {
        agentJob?.cancel()
        _agent.update { it.copy(loading = false, result = null, comparison = emptyList()) }
    }
}

class LauncherViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LauncherViewModel(container) as T
}
