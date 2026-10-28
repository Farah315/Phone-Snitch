package com.farah.phonesnitch.data.repository

import android.content.Context
import android.content.Intent
import com.farah.phonesnitch.data.local.AppCategoryEntity
import com.farah.phonesnitch.data.local.AppLaunchEntity
import com.farah.phonesnitch.data.local.AppPrefEntity
import com.farah.phonesnitch.data.local.LauncherDao
import com.farah.phonesnitch.data.local.UsageRow
import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.AppClassification
import com.farah.phonesnitch.domain.model.AppInfo
import com.farah.phonesnitch.domain.model.AppPreference
import com.farah.phonesnitch.domain.model.LaunchRecord
import com.farah.phonesnitch.domain.model.TimeSlot
import com.farah.phonesnitch.domain.model.UsageCount
import com.farah.phonesnitch.domain.repository.LauncherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class LauncherRepositoryImpl(
    private val context: Context,
    private val dao: LauncherDao,
) : LauncherRepository {

    override fun observeClassifications(): Flow<Map<String, AppClassification>> =
        dao.observeCategories().map { rows -> rows.associate { it.packageName to it.toDomain() } }

    override fun observeUsage(): Flow<List<UsageCount>> =
        dao.observeUsage(windowStart()).map { it.toUsage() }

    override suspend fun usageSnapshot(): List<UsageCount> = dao.usage(windowStart()).toUsage()

    override fun observeLaunches(): Flow<List<LaunchRecord>> =
        dao.observeLaunches(windowStart()).map { rows -> rows.map { LaunchRecord(it.packageName, it.timestamp) } }

    override suspend fun recordLaunch(packageName: String, timestampMillis: Long) {
        val hour = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).hour
        dao.insertLaunch(
            AppLaunchEntity(
                packageName = packageName,
                timestamp = timestampMillis,
                slot = TimeSlot.fromHour(hour).key,
            )
        )
    }

    override suspend fun saveClassifications(items: List<AppClassification>) {
        val now = System.currentTimeMillis()
        dao.upsertCategories(
            items.map {
                AppCategoryEntity(
                    packageName = it.packageName,
                    category = it.category.key,
                    confidence = it.confidence,
                    peakSlots = it.peakSlots.joinToString(",") { slot -> slot.key },
                    updatedAt = now,
                )
            }
        )
    }

    override fun observePreferences(): Flow<Map<String, AppPreference>> =
        dao.observePrefs().map { rows ->
            rows.associate { row ->
                row.packageName to AppPreference(
                    packageName = row.packageName,
                    pinned = row.pinned,
                    categoryOverride = row.categoryOverride?.let { AppCategory.fromKey(it) },
                    dailyLimitMinutes = row.dailyLimitMinutes,
                )
            }
        }

    override suspend fun setPinned(packageName: String, pinned: Boolean) {
        val current = dao.getPref(packageName) ?: AppPrefEntity(packageName)
        dao.upsertPref(current.copy(pinned = pinned))
    }

    override suspend fun setCategoryOverride(packageName: String, category: AppCategory?) {
        val current = dao.getPref(packageName) ?: AppPrefEntity(packageName)
        dao.upsertPref(current.copy(categoryOverride = category?.key))
    }

    override suspend fun setDailyLimit(packageName: String, minutes: Int?) {
        val current = dao.getPref(packageName) ?: AppPrefEntity(packageName)
        dao.upsertPref(current.copy(dailyLimitMinutes = minutes))
    }

    override suspend fun importLaunches(records: List<LaunchRecord>, replaceSince: Long?) {
        if (replaceSince != null) dao.deleteLaunchesSince(replaceSince)
        dao.insertLaunches(
            records.map {
                val hour = Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).hour
                AppLaunchEntity(
                    packageName = it.packageName,
                    timestamp = it.timestamp,
                    slot = TimeSlot.fromHour(hour).key,
                )
            }
        )
    }

    override suspend fun loadInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun windowStart(): Long = System.currentTimeMillis() - WINDOW_DAYS * DAY_MILLIS

    private fun List<UsageRow>.toUsage(): List<UsageCount> = mapNotNull { row ->
        TimeSlot.fromKey(row.slot)?.let { UsageCount(row.packageName, it, row.cnt) }
    }

    private fun AppCategoryEntity.toDomain() = AppClassification(
        packageName = packageName,
        category = AppCategory.fromKey(category),
        confidence = confidence,
        peakSlots = peakSlots.split(",").mapNotNull { TimeSlot.fromKey(it) }.toSet(),
    )

    private companion object {
        const val WINDOW_DAYS = 14L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
