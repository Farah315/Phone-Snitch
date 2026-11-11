package com.farah.phonesnitch.data.system

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.farah.phonesnitch.domain.model.LaunchRecord
import com.farah.phonesnitch.domain.repository.LauncherRepository
import com.farah.phonesnitch.domain.repository.SettingsRepository
import com.farah.phonesnitch.domain.repository.UsageStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Thin wrapper over UsageStatsManager: permission check + turning raw events into opens and screen time. */
class UsageStatsSource(private val context: Context) {

    class Scan(val opens: List<LaunchRecord>, val foregroundMillis: Map<String, Long>)

    private val manager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * An "open" = an app comes to the foreground after not being in it (a new session).
     * Moving between screens of the same app, or a quick pause/resume (< 3s), is the same session.
     * Foreground time = time between resume and pause, summed per package.
     */
    fun scan(from: Long, to: Long): Scan {
        val events = manager.queryEvents(from, to)
        val event = UsageEvents.Event()

        val opens = ArrayList<LaunchRecord>()
        val millis = HashMap<String, Long>()
        val depth = HashMap<String, Int>()      // how many activities of the package are resumed
        val startedAt = HashMap<String, Long>() // session start while in foreground
        val pausedAt = HashMap<String, Long>()  // last time the session ended

        fun closeSession(pkg: String, at: Long) {
            val start = startedAt.remove(pkg) ?: return
            millis[pkg] = (millis[pkg] ?: 0L) + (at - start).coerceAtLeast(0L)
            pausedAt[pkg] = at
            depth[pkg] = 0
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val ts = event.timeStamp
            when (event.eventType) {
                RESUMED -> {
                    val d = (depth[pkg] ?: 0) + 1
                    depth[pkg] = d
                    if (d == 1) {
                        val lastPause = pausedAt[pkg]
                        val sameSession = lastPause != null && ts - lastPause <= SAME_SESSION_GAP_MS
                        if (!sameSession) opens += LaunchRecord(pkg, ts)
                        startedAt[pkg] = ts
                    }
                }
                PAUSED -> {
                    val d = (depth[pkg] ?: 0) - 1
                    if (d <= 0) closeSession(pkg, ts) else depth[pkg] = d
                }
                SCREEN_OFF -> {
                    // Screen off ends every session, so unlocking into the same app counts as a new open.
                    startedAt.keys.toList().forEach { closeSession(it, ts) }
                    pausedAt.clear()
                }
            }
        }

        // Whatever is still in the foreground counts up to "now".
        startedAt.forEach { (pkg, start) ->
            millis[pkg] = (millis[pkg] ?: 0L) + (to - start).coerceAtLeast(0L)
        }
        return Scan(opens, millis)
    }

    private companion object {
        // Same numeric values as Event.ACTIVITY_RESUMED / ACTIVITY_PAUSED (API 29) and the older
        // MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND, so this also works on Android 8-9.
        const val RESUMED = 1
        const val PAUSED = 2
        const val SCREEN_OFF = 16 // SCREEN_NON_INTERACTIVE
        const val SAME_SESSION_GAP_MS = 3_000L
    }
}

class UsageStatsRepositoryImpl(
    private val source: UsageStatsSource,
    private val launcherRepository: LauncherRepository,
    private val settings: SettingsRepository,
) : UsageStatsRepository {
    private val importMutex = Mutex()

    override fun hasAccess(): Boolean = source.hasAccess()

    override suspend fun importOpens(): Int = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val now = System.currentTimeMillis()
            val last = settings.lastUsageImportMillis
            val firstImport = last <= 0L
            // Android only keeps a few days of events, 7 is a safe window for the first import.
            val from = if (firstImport) now - FIRST_IMPORT_DAYS * DAY_MILLIS else last

            val installed = launcherRepository.loadInstalledApps().map { it.packageName }.toSet()
            val opens = source.scan(from, now).opens.filter { it.packageName in installed }

            launcherRepository.importLaunches(opens, replaceSince = if (firstImport) from else null)
            settings.lastUsageImportMillis = now
            opens.size
        }
    }

    override suspend fun todayForegroundMillis(): Map<String, Long> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val installed = launcherRepository.loadInstalledApps().map { it.packageName }.toSet()
        source.scan(startOfDay, System.currentTimeMillis()).foregroundMillis.filterKeys { it in installed }
    }

    private companion object {
        const val FIRST_IMPORT_DAYS = 7L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
