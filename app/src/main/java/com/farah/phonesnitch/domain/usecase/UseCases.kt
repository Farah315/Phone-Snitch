package com.farah.phonesnitch.domain.usecase

import com.farah.phonesnitch.domain.AppRules
import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.AppClassification
import com.farah.phonesnitch.domain.model.AppInfo
import com.farah.phonesnitch.domain.model.AppUsageInput
import com.farah.phonesnitch.domain.model.DetoxStage
import com.farah.phonesnitch.domain.model.HomeSection
import com.farah.phonesnitch.domain.model.TimeSlot
import com.farah.phonesnitch.domain.model.UsageCount
import com.farah.phonesnitch.domain.repository.AiService
import com.farah.phonesnitch.domain.repository.LauncherRepository
import com.farah.phonesnitch.domain.repository.SettingsRepository
import com.farah.phonesnitch.domain.model.AppOpenStat
import com.farah.phonesnitch.domain.model.AppPreference
import com.farah.phonesnitch.domain.model.AppTime
import com.farah.phonesnitch.domain.model.LimitStat
import com.farah.phonesnitch.domain.model.TimeStats
import com.farah.phonesnitch.domain.model.DashboardStats
import com.farah.phonesnitch.domain.model.DayCount
import com.farah.phonesnitch.domain.model.LaunchRecord
import com.farah.phonesnitch.domain.model.RecentLaunch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private data class RankedSection(val section: HomeSection, val score: Double, val totalUsage: Int)

/**
 * Decides what the first screen shows for the current time slot.
 *
 * score(app, slot) = launches in this slot (real data, last 14 days)
 *                  + PEAK_BONUS * confidence   if the AI said this app peaks in this slot
 *
 * On day 1 there is no real data, so the AI's peak_slots drive the order.
 * As real usage accumulates it takes over naturally.
 */
class BuildHomeSectionsUseCase {

    operator fun invoke(
        apps: List<AppInfo>,
        classifications: Map<String, AppClassification>,
        usage: List<UsageCount>,
        slot: TimeSlot,
        stage: DetoxStage,
    ): List<HomeSection> {
        val usageByApp = usage.groupBy { it.packageName }

        fun totalUsage(pkg: String): Int = usageByApp[pkg].orEmpty().sumOf { it.count }

        fun slotScore(pkg: String): Double {
            val inSlot = usageByApp[pkg].orEmpty().filter { it.slot == slot }.sumOf { it.count }
            val cls = classifications[pkg]
            val bonus = if (cls != null && slot in cls.peakSlots) PEAK_BONUS * cls.confidence else 0.0
            return inSlot + bonus
        }

        val hideSocial = stage == DetoxStage.HIDDEN_FROM_HOME || stage == DetoxStage.WEEKLY_BLOCK
        val candidates = if (hideSocial) {
            apps.filterNot { AppRules.isSocial(it.packageName, classifications[it.packageName]) }
        } else {
            apps
        }

        val sections = candidates
            .groupBy { AppRules.categoryOf(it.packageName, classifications[it.packageName]) }
            .map { (category, list) ->
                val sortedApps = list.sortedWith(
                    compareByDescending<AppInfo> { slotScore(it.packageName) }
                        .thenByDescending { totalUsage(it.packageName) }
                        .thenBy { it.label.lowercase() }
                )
                RankedSection(
                    section = HomeSection(category, sortedApps.take(MAX_APPS_PER_SECTION)),
                    score = list.sumOf { slotScore(it.packageName) },
                    totalUsage = list.sumOf { totalUsage(it.packageName) },
                )
            }
            .sortedWith(
                compareByDescending<RankedSection> { it.score }
                    .thenByDescending { it.totalUsage }
                    .thenBy { it.section.category.ordinal }
            )
            .map { it.section }

        if (stage != DetoxStage.REDUCED) return sections.take(MAX_SECTIONS)

        // Detox stage 1: social media can never rank above other categories, however much it's used.
        val (social, others) = sections.partition { it.category == AppCategory.SOCIAL_MEDIA }
        val demoted = social.map { it.copy(apps = it.apps.take(REDUCED_SOCIAL_APPS)) }
        return (others + demoted).take(MAX_SECTIONS)
    }

    private companion object {
        const val PEAK_BONUS = 3.0
        const val MAX_SECTIONS = 5
        const val MAX_APPS_PER_SECTION = 8
        const val REDUCED_SOCIAL_APPS = 3
    }
}

/** Runs the daily AI categorization (sends all apps + their usage per slot, in batches). */
class ClassifyAppsUseCase(
    private val repository: LauncherRepository,
    private val ai: AiService,
    private val settings: SettingsRepository,
) {
    /** @return true if a classification ran, false if skipped (already done today / no apps). */
    suspend operator fun invoke(force: Boolean): Boolean {
        val today = LocalDate.now().toEpochDay()
        if (!force && settings.lastClassifiedEpochDay == today) return false

        val apps = repository.loadInstalledApps()
        if (apps.isEmpty()) return false

        val usageByApp = repository.usageSnapshot().groupBy { it.packageName }
        val inputs = apps.map { app ->
            AppUsageInput(
                app = app,
                usage = usageByApp[app.packageName].orEmpty().associate { it.slot to it.count },
            )
        }

        // Batches keep each prompt small and make one bad response cheap to retry.
        for (batch in inputs.chunked(BATCH_SIZE)) {
            repository.saveClassifications(ai.classify(batch))
        }

        settings.lastClassifiedEpochDay = today
        return true
    }

    private companion object {
        const val BATCH_SIZE = 40
    }
}

class AskAssistantUseCase(private val ai: AiService) {
    suspend operator fun invoke(question: String): String = ai.ask(question.trim())
}

/** Screen time per app today + progress of every daily limit. Empty without Usage Access. */
class BuildTimeStatsUseCase {
    operator fun invoke(
        todayMillis: Map<String, Long>,
        apps: List<AppInfo>,
        prefs: Map<String, AppPreference>,
    ): TimeStats {
        val labels = apps.associate { it.packageName to it.label }
        fun usedMinutes(pkg: String) = ((todayMillis[pkg] ?: 0L) / 60_000L).toInt()

        val timeToday = todayMillis
            .filterKeys { it in labels }
            .map { (pkg, ms) -> AppTime(pkg, labels.getValue(pkg), ((ms + 30_000L) / 60_000L).toInt()) }
            .filter { it.minutes >= 1 }
            .sortedByDescending { it.minutes }
            .take(10)

        val total = (todayMillis.filterKeys { it in labels }.values.sum() / 60_000L).toInt()

        val limits = prefs.values.mapNotNull { pref ->
            val limit = pref.dailyLimitMinutes ?: return@mapNotNull null
            val label = labels[pref.packageName] ?: return@mapNotNull null
            LimitStat(pref.packageName, label, usedMinutes(pref.packageName), limit)
        }.sortedByDescending { it.usedMinutes.toFloat() / it.limitMinutes }

        return TimeStats(total, timeToday, limits)
    }
}

/** Turns the raw launch log into the numbers shown on the Dashboard (last 7 days). */
class BuildDashboardStatsUseCase {

    private data class Entry(val record: LaunchRecord, val date: LocalDate, val hour: Int)

    operator fun invoke(
        launches: List<LaunchRecord>,
        apps: List<AppInfo>,
        classifications: Map<String, AppClassification>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DashboardStats {
        val labels = apps.associate { it.packageName to it.label }
        fun labelOf(pkg: String) = labels[pkg] ?: pkg

        val entries = launches.map {
            val time = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            Entry(it, time.toLocalDate(), time.hour)
        }

        val firstDay = today.minusDays(6)
        val week = entries.filter { it.date >= firstDay && it.date <= today }
        val todayEntries = week.filter { it.date == today }

        val perDay = (0L..6L).map { offset ->
            val day = firstDay.plusDays(offset)
            DayCount(day, week.count { it.date == day })
        }

        val slotCounts = week.groupingBy { TimeSlot.fromHour(it.hour) }.eachCount()
        val perSlot = TimeSlot.entries.associateWith { slotCounts[it] ?: 0 }

        val perCategory = week
            .groupingBy { AppRules.categoryOf(it.record.packageName, classifications[it.record.packageName]) }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }

        val topApps = week.groupBy { it.record.packageName }
            .map { (pkg, list) ->
                AppOpenStat(
                    packageName = pkg,
                    label = labelOf(pkg),
                    count = list.size,
                    lastOpened = list.maxOf { it.record.timestamp },
                )
            }
            .sortedWith(compareByDescending<AppOpenStat> { it.count }.thenByDescending { it.lastOpened })
            .take(10)

        val recent = launches
            .sortedByDescending { it.timestamp }
            .take(30)
            .map { RecentLaunch(it.packageName, labelOf(it.packageName), it.timestamp) }

        return DashboardStats(
            opensToday = todayEntries.size,
            opensLast7Days = week.size,
            distinctAppsToday = todayEntries.map { it.record.packageName }.toSet().size,
            perDay = perDay,
            perSlot = perSlot,
            perCategory = perCategory,
            topApps = topApps,
            recent = recent,
        )
    }
}
