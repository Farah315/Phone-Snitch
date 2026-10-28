package com.farah.phonesnitch.domain.model

import java.time.LocalDate

/** Part of the day. The AI and the ranking logic both speak in these four slots. */
enum class TimeSlot(val key: String, val label: String) {
    MORNING("morning", "Morning"),
    WORK_HOURS("work_hours", "Work hours"),
    EVENING("evening", "Evening"),
    NIGHT("night", "Night");

    companion object {
        fun fromHour(hour: Int): TimeSlot = when (hour) {
            in 5..8 -> MORNING
            in 9..16 -> WORK_HOURS
            in 17..21 -> EVENING
            else -> NIGHT
        }

        fun fromKey(key: String): TimeSlot? = entries.firstOrNull { it.key == key.trim() }
    }
}

/** Fixed category list: the AI may only answer with one of these keys. */
enum class AppCategory(val key: String, val label: String) {
    WORK("work", "Work"),
    COMMUNICATION("communication", "Communication"),
    SOCIAL_MEDIA("social_media", "Social media"),
    ENTERTAINMENT("entertainment", "Entertainment"),
    EDUCATION("education", "Education"),
    PRODUCTIVITY("productivity", "Productivity"),
    FINANCE("finance", "Finance"),
    SHOPPING("shopping", "Shopping"),
    TRAVEL("travel", "Travel"),
    HEALTH("health", "Health"),
    GAMES("games", "Games"),
    TOOLS("tools", "Tools"),
    OTHER("other", "Other");

    companion object {
        /** Unknown values fall back to OTHER instead of crashing the launcher. */
        fun fromKey(key: String): AppCategory =
            entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) } ?: OTHER
    }
}

data class AppInfo(val packageName: String, val label: String)

data class AppClassification(
    val packageName: String,
    val category: AppCategory,
    val confidence: Float,
    val peakSlots: Set<TimeSlot>,
)

/** How many times an app was opened from the launcher in a given slot. */
data class UsageCount(val packageName: String, val slot: TimeSlot, val count: Int)

data class HomeSection(val category: AppCategory, val apps: List<AppInfo>)

data class AppUsageInput(val app: AppInfo, val usage: Map<TimeSlot, Int>)

enum class DetoxStage { OFF, REDUCED, HIDDEN_FROM_HOME, WEEKLY_BLOCK }

/** The user's own choices. They always win over what the AI decided. */
data class AppPreference(
    val packageName: String,
    val pinned: Boolean,
    val categoryOverride: AppCategory?,
    val dailyLimitMinutes: Int? = null,
)

/** One row in the Customize screen. */
data class CustomizeItem(
    val app: AppInfo,
    val category: AppCategory,
    val categoryOverridden: Boolean,
    val pinned: Boolean,
    val dailyLimitMinutes: Int?,
)

// ---- Dashboard ----

/** One recorded app opening (what the launcher logs on every tap). */
data class LaunchRecord(val packageName: String, val timestamp: Long)

data class DayCount(val date: LocalDate, val count: Int)

data class AppOpenStat(val packageName: String, val label: String, val count: Int, val lastOpened: Long)

data class RecentLaunch(val packageName: String, val label: String, val timestamp: Long)

// ---- Screen time (needs Usage Access) ----

data class AppTime(val packageName: String, val label: String, val minutes: Int)

data class LimitStat(
    val packageName: String,
    val label: String,
    val usedMinutes: Int,
    val limitMinutes: Int,
) {
    val reached: Boolean get() = usedMinutes >= limitMinutes
}

data class TimeStats(
    val totalMinutesToday: Int,
    val timeToday: List<AppTime>,
    val limits: List<LimitStat>,
) {
    val reachedPackages: Set<String> get() = limits.filter { it.reached }.map { it.packageName }.toSet()

    companion object {
        val EMPTY = TimeStats(0, emptyList(), emptyList())
    }
}

data class DashboardStats(
    val opensToday: Int,
    val opensLast7Days: Int,
    val distinctAppsToday: Int,
    val perDay: List<DayCount>,
    val perSlot: Map<TimeSlot, Int>,
    val perCategory: List<Pair<AppCategory, Int>>,
    val topApps: List<AppOpenStat>,
    val recent: List<RecentLaunch>,
)
