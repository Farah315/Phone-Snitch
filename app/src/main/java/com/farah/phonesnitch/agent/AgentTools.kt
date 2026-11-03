package com.farah.phonesnitch.agent

import com.farah.phonesnitch.data.system.AppLauncher
import com.farah.phonesnitch.domain.AppRules
import com.farah.phonesnitch.domain.DetoxPolicy
import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.AppInfo
import com.farah.phonesnitch.domain.model.DetoxStage
import com.farah.phonesnitch.domain.repository.LauncherRepository
import com.farah.phonesnitch.domain.repository.SettingsRepository
import com.farah.phonesnitch.domain.repository.UsageStatsRepository
import com.farah.phonesnitch.domain.usecase.BuildDashboardStatsUseCase
import com.farah.phonesnitch.domain.usecase.BuildTimeStatsUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What a tool returns to the model. [finalMessage] ends the agent loop early (e.g. after opening an app). */
class ToolOutcome(val json: JsonObject, val ok: Boolean = true, val finalMessage: String? = null)

/**
 * The functions the model is allowed to call. This is the "tools" part of the agent:
 * we describe each function to the model ([declarations]) and run it when the model asks ([execute]).
 *
 * In dry-run mode (used by "Compare models") tools that change something only describe what they would do.
 */
class AgentTools(
    private val repository: LauncherRepository,
    private val settings: SettingsRepository,
    private val usageStats: UsageStatsRepository,
    private val appLauncher: AppLauncher,
) {
    private val dashboardStats = BuildDashboardStatsUseCase()
    private val timeStats = BuildTimeStatsUseCase()
    private val usageImportMutex = Mutex()

    // ---------------------------------------------------------------- declarations (what the model sees)

    private class Param(
        val name: String,
        val type: String,
        val description: String,
        val enum: List<String>? = null,
    )

    private fun declaration(name: String, description: String, vararg params: Param): JsonObject =
        buildJsonObject {
            put("name", name)
            put("description", description)
            if (params.isNotEmpty()) {
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        params.forEach { p ->
                            putJsonObject(p.name) {
                                put("type", p.type)
                                put("description", p.description)
                                if (p.enum != null) putJsonArray("enum") { p.enum.forEach { add(JsonPrimitive(it)) } }
                            }
                        }
                    }
                    putJsonArray("required") { params.forEach { add(JsonPrimitive(it.name)) } }
                }
            }
        }

    val declarations: JsonArray = buildJsonArray {
        add(
            declaration(
                "find_apps",
                "Search the installed apps by name, package or category (e.g. 'insta', 'social_media'). " +
                    "Use it when you are not sure of the exact app name. Returns up to 10 matches.",
                Param("query", "STRING", "Part of the app name, or a category key."),
            )
        )
        add(
            declaration(
                "open_app",
                "Open an installed app. It refuses if the app is blocked today by Social Media Detox " +
                    "or has reached its daily limit.",
                Param("app_name", "STRING", "App name as shown on the phone, e.g. 'WhatsApp'."),
            )
        )
        add(
            declaration(
                "get_usage_stats",
                "Usage numbers for the whole phone: number of app opens, most opened apps, and screen time " +
                    "(screen time needs Usage Access). Always use this for usage questions, never guess.",
                Param("period", "STRING", "'today' or 'week'.", enum = listOf("today", "week")),
            )
        )
        add(
            declaration(
                "get_app_usage",
                "Usage numbers for ONE app: opens today, opens in the last 7 days, minutes today, daily limit.",
                Param("app_name", "STRING", "App name as shown on the phone."),
            )
        )
        add(
            declaration(
                "set_daily_limit",
                "Set a daily time limit for an app, in minutes. Use 0 to remove the limit. " +
                    "When the limit is reached the launcher refuses to open the app.",
                Param("app_name", "STRING", "App name as shown on the phone."),
                Param("minutes", "INTEGER", "Minutes per day, 1 to 1440. 0 removes the limit."),
            )
        )
        add(
            declaration(
                "set_social_detox",
                "Turn the Social Media Detox on or off. When turned on it hides social apps step by step.",
                Param("enabled", "BOOLEAN", "true to turn on, false to turn off."),
            )
        )
        add(
            declaration(
                "pin_app",
                "Pin an app to the top of the home screen, or unpin it.",
                Param("app_name", "STRING", "App name as shown on the phone."),
                Param("pinned", "BOOLEAN", "true to pin, false to unpin."),
            )
        )
        add(
            declaration(
                "set_app_category",
                "Change the category the launcher uses for an app.",
                Param("app_name", "STRING", "App name as shown on the phone."),
                Param(
                    "category", "STRING", "The new category.",
                    enum = AppCategory.entries.map { it.key },
                ),
            )
        )
    }

    // ---------------------------------------------------------------- execution (what runs on the phone)

    suspend fun execute(name: String, args: JsonObject, dryRun: Boolean): ToolOutcome = try {
        when (name) {
            "find_apps" -> findApps(args.str("query").orEmpty())
            "open_app" -> openApp(args.str("app_name"), dryRun)
            "get_usage_stats" -> usageStatsFor(args.str("period") ?: "today")
            "get_app_usage" -> appUsage(args.str("app_name"))
            "set_daily_limit" -> setDailyLimit(args.str("app_name"), args.int("minutes"), dryRun)
            "set_social_detox" -> setDetox(args.bool("enabled"), dryRun)
            "pin_app" -> pinApp(args.str("app_name"), args.bool("pinned"), dryRun)
            "set_app_category" -> setCategory(args.str("app_name"), args.str("category"), dryRun)
            else -> fail("Unknown tool '$name'")
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(e.message ?: "Tool failed")
    }

    private suspend fun findApps(query: String): ToolOutcome {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return fail("query is empty")
        val prefs = repository.observePreferences().first()
        val classifications = classifications(prefs)
        val matches = repository.loadInstalledApps().filter { app ->
            val category = AppRules.categoryOf(app.packageName, classifications[app.packageName])
            app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q) ||
                category.key == q ||
                category.label.lowercase() == q
        }.take(10)

        return success {
            put("count", matches.size)
            putJsonArray("apps") {
                matches.forEach { app ->
                    add(buildJsonObject {
                        put("name", app.label)
                        put("category", AppRules.categoryOf(app.packageName, classifications[app.packageName]).key)
                        put("pinned", prefs[app.packageName]?.pinned == true)
                        prefs[app.packageName]?.dailyLimitMinutes?.let { put("daily_limit_minutes", it) }
                    })
                }
            }
        }
    }

    private suspend fun openApp(appName: String?, dryRun: Boolean): ToolOutcome {
        val app = when (val found = resolve(appName)) {
            is Resolved.One -> found.app
            is Resolved.Problem -> return found.outcome
        }
        val prefs = repository.observePreferences().first()
        val classifications = classifications(prefs)
        val today = LocalDate.now()

        // Same rules as tapping the icon: the agent cannot be used to dodge the detox or a limit.
        val stage = detoxStage(today)
        if (DetoxPolicy.isBlockedToday(stage, today) &&
            AppRules.isSocial(app.packageName, classifications[app.packageName])
        ) {
            return fail("${app.label} is blocked today by Social Media Detox")
        }
        val limit = prefs[app.packageName]?.dailyLimitMinutes
        if (limit != null && usageStats.hasAccess()) {
            val used = minutes(usageStats.todayForegroundMillis()[app.packageName])
            if (used >= limit) return fail("Daily limit reached for ${app.label} ($used of $limit min)")
        }

        if (dryRun) return dryRunOutcome("open ${app.label}")
        if (!appLauncher.launch(app.packageName)) return fail("Could not open ${app.label}")
        // Without Usage Access the launcher is the only one who knows about this open.
        if (!usageStats.hasAccess()) repository.recordLaunch(app.packageName, System.currentTimeMillis())

        return ToolOutcome(
            json = buildJsonObject { put("opened", app.label) },
            finalMessage = "Opening ${app.label}",
        )
    }

    private suspend fun usageStatsFor(period: String): ToolOutcome {
        refreshUsageImports()
        val apps = repository.loadInstalledApps()
        val prefs = repository.observePreferences().first()
        val stats = dashboardStats(
            launches = repository.observeLaunches().first(),
            apps = apps,
            classifications = classifications(prefs),
            today = LocalDate.now(),
        )
        val access = usageStats.hasAccess()
        val time = if (access) {
            timeStats(usageStats.todayForegroundMillis(), apps, prefs)
        } else {
            null
        }

        return success {
            put("period", period)
            put("usage_access_granted", access)
            if (period == "week") {
                put("opens_last_7_days", stats.opensLast7Days)
                putJsonArray("opens_per_day") {
                    stats.perDay.forEach { add(buildJsonObject { put("date", it.date.toString()); put("opens", it.count) }) }
                }
                putJsonArray("most_opened_apps") {
                    stats.topApps.take(5).forEach { add(buildJsonObject { put("name", it.label); put("opens", it.count) }) }
                }
                putJsonObject("opens_by_time_of_day") {
                    stats.perSlot.forEach { (slot, count) -> put(slot.key, count) }
                }
            } else {
                put("opens_today", stats.opensToday)
                put("different_apps_opened_today", stats.distinctAppsToday)
                if (time != null) {
                    put("screen_time_minutes_today", time.totalMinutesToday)
                    putJsonArray("most_used_apps_today") {
                        time.timeToday.take(5).forEach {
                            add(buildJsonObject { put("name", it.label); put("minutes", it.minutes) })
                        }
                    }
                } else {
                    put("note", "Screen time is unavailable because Usage Access is not granted.")
                }
            }
        }
    }

    private suspend fun appUsage(appName: String?): ToolOutcome {
        val app = when (val found = resolve(appName)) {
            is Resolved.One -> found.app
            is Resolved.Problem -> return found.outcome
        }
        refreshUsageImports()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val mine = repository.observeLaunches().first().filter { it.packageName == app.packageName }
        fun dateOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val limit = repository.observePreferences().first()[app.packageName]?.dailyLimitMinutes
        val access = usageStats.hasAccess()
        // Suspend calls must happen before the (non-suspend) JSON builder below.
        val minutesToday = if (access) minutes(usageStats.todayForegroundMillis()[app.packageName]) else 0

        return success {
            put("app", app.label)
            put("opens_today", mine.count { dateOf(it.timestamp) == today })
            put("opens_last_7_days", mine.count { dateOf(it.timestamp) >= today.minusDays(6) })
            if (access) {
                put("minutes_today", minutesToday)
            } else {
                put("note", "Minutes are unavailable because Usage Access is not granted.")
            }
            if (limit != null) put("daily_limit_minutes", limit)
        }
    }

    private suspend fun setDailyLimit(appName: String?, minutesArg: Int?, dryRun: Boolean): ToolOutcome {
        val app = when (val found = resolve(appName)) {
            is Resolved.One -> found.app
            is Resolved.Problem -> return found.outcome
        }
        val value = minutesArg ?: return fail("minutes is missing")
if (value < 0 || value > MAX_LIMIT_MINUTES) return fail("minutes must be between 0 and $MAX_LIMIT_MINUTES")
        val limit = if (value == 0) null else value

        if (dryRun) {
            return dryRunOutcome(if (limit == null) "remove the limit of ${app.label}" else "limit ${app.label} to $limit min per day")
        }
        repository.setDailyLimit(app.packageName, limit)
        return success {
            put("app", app.label)
            if (limit == null) put("limit", "removed") else put("daily_limit_minutes", limit)
            if (!usageStats.hasAccess()) {
                put("note", "Saved, but limits are only enforced after Usage Access is granted.")
            }
        }
    }

    private suspend fun setDetox(enabled: Boolean?, dryRun: Boolean): ToolOutcome {
        val value = enabled ?: return fail("enabled is missing")
        if (dryRun) return dryRunOutcome(if (value) "turn Social Media Detox on" else "turn Social Media Detox off")
        // Do not restart the day counter if it is already in the requested state.
        if (settings.detoxEnabled.value != value) {
            settings.setDetoxEnabled(value, LocalDate.now().toEpochDay())
        }
        return success { put("social_detox_enabled", value) }
    }

    private suspend fun pinApp(appName: String?, pinned: Boolean?, dryRun: Boolean): ToolOutcome {
        val app = when (val found = resolve(appName)) {
            is Resolved.One -> found.app
            is Resolved.Problem -> return found.outcome
        }
        val value = pinned ?: return fail("pinned is missing")
        if (dryRun) return dryRunOutcome((if (value) "pin " else "unpin ") + app.label)
        repository.setPinned(app.packageName, value)
        return success { put("app", app.label); put("pinned", value) }
    }

    private suspend fun setCategory(appName: String?, categoryKey: String?, dryRun: Boolean): ToolOutcome {
        val app = when (val found = resolve(appName)) {
            is Resolved.One -> found.app
            is Resolved.Problem -> return found.outcome
        }
        val category = AppCategory.entries.firstOrNull { it.key.equals(categoryKey?.trim(), ignoreCase = true) }
            ?: return fail("Unknown category '$categoryKey'. Use one of: " + AppCategory.entries.joinToString { it.key })
        if (dryRun) return dryRunOutcome("set the category of ${app.label} to ${category.key}")
        repository.setCategoryOverride(app.packageName, category)
        return success { put("app", app.label); put("category", category.key) }
    }

    // ---------------------------------------------------------------- helpers

    private sealed interface Resolved {
        class One(val app: AppInfo) : Resolved
        class Problem(val outcome: ToolOutcome) : Resolved
    }

    /** Finds one installed app from the (possibly sloppy) name the model sends. */
    private suspend fun resolve(query: String?): Resolved {
        val q = normalize(query.orEmpty())
        if (q.isEmpty()) return Resolved.Problem(fail("app_name is missing"))
        val apps = repository.loadInstalledApps()

        apps.filter { normalize(it.label) == q || normalize(it.packageName) == q }
            .singleOrNull()?.let { return Resolved.One(it) }

        val partial = apps.filter { normalize(it.label).contains(q) || normalize(it.packageName).contains(q) }
        return when {
            partial.size == 1 -> Resolved.One(partial.first())
            partial.isEmpty() -> Resolved.Problem(fail("No installed app matches '$query'. Try find_apps."))
            else -> Resolved.Problem(
                fail("'$query' matches several apps: " + partial.take(6).joinToString { it.label } + ". Ask which one.")
            )
        }
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun classifications(prefs: Map<String, com.farah.phonesnitch.domain.model.AppPreference>) =
        AppRules.applyOverrides(repository.observeClassifications().first(), prefs)

    private suspend fun refreshUsageImports() {
        if (!usageStats.hasAccess()) return
        usageImportMutex.withLock { usageStats.importOpens() }
    }

    private fun detoxStage(today: LocalDate): DetoxStage =
        DetoxPolicy.stage(
            enabled = settings.detoxEnabled.value,
            startDate = settings.detoxStartEpochDay.value?.let { LocalDate.ofEpochDay(it) },
            today = today,
        )

    private fun minutes(millis: Long?): Int = ((millis ?: 0L) / 60_000L).toInt()

    private fun success(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ToolOutcome(buildJsonObject(build))

    private fun fail(message: String) =
        ToolOutcome(buildJsonObject { put("error", message) }, ok = false)

    private fun dryRunOutcome(action: String) =
        ToolOutcome(buildJsonObject { put("dry_run", true); put("would", action) })

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
        it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { s -> s == "true" }
    }

    private companion object {
        const val MAX_LIMIT_MINUTES = 1440
    }
}
