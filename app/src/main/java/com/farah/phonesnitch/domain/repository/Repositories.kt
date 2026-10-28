package com.farah.phonesnitch.domain.repository

import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.AppClassification
import com.farah.phonesnitch.domain.model.AppPreference
import com.farah.phonesnitch.domain.model.LaunchRecord
import com.farah.phonesnitch.domain.model.AppInfo
import com.farah.phonesnitch.domain.model.AppUsageInput
import com.farah.phonesnitch.domain.model.UsageCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LauncherRepository {
    fun observeClassifications(): Flow<Map<String, AppClassification>>

    /** Launch counts per app and time slot over the last 14 days. */
    fun observeUsage(): Flow<List<UsageCount>>
    suspend fun usageSnapshot(): List<UsageCount>

    /** Raw launch log (last 14 days), newest first. Feeds the Dashboard. */
    fun observeLaunches(): Flow<List<LaunchRecord>>

    suspend fun recordLaunch(packageName: String, timestampMillis: Long)
    suspend fun saveClassifications(items: List<AppClassification>)
    suspend fun loadInstalledApps(): List<AppInfo>

    fun observePreferences(): Flow<Map<String, AppPreference>>
    suspend fun setPinned(packageName: String, pinned: Boolean)

    /** Pass null to go back to the AI's category. */
    suspend fun setCategoryOverride(packageName: String, category: AppCategory?)

    /** Pass null to remove the limit. */
    suspend fun setDailyLimit(packageName: String, minutes: Int?)

    /**
     * Stores opens read from Android's usage stats. If [replaceSince] is set, launcher-recorded
     * rows from that moment on are replaced (usage stats already contain them, so they'd be counted twice).
     */
    suspend fun importLaunches(records: List<LaunchRecord>, replaceSince: Long?)
}

/** Reads Android's UsageStatsManager. Everything here needs the "Usage access" permission. */
interface UsageStatsRepository {
    fun hasAccess(): Boolean

    /** Imports opens made since the last import (from any place, not only this launcher). */
    suspend fun importOpens(): Int

    /** Foreground time per package since local midnight, in milliseconds. */
    suspend fun todayForegroundMillis(): Map<String, Long>
}

interface AiService {
    suspend fun classify(apps: List<AppUsageInput>): List<AppClassification>
    suspend fun ask(question: String): String
}

interface SettingsRepository {
    val detoxEnabled: StateFlow<Boolean>
    val detoxStartEpochDay: StateFlow<Long?>
    var lastClassifiedEpochDay: Long

    /** False until the user has seen the first-run Customize screen once. */
    var onboardingDone: Boolean

    /** When usage stats were last imported into the launch log (0 = never). */
    var lastUsageImportMillis: Long

    /** Enabling restarts the detox from day 1. Disabling clears the start date. */
    fun setDetoxEnabled(enabled: Boolean, todayEpochDay: Long)
}
