package com.farah.phonesnitch.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.farah.phonesnitch.PhoneSnitchApp
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class ClassifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PhoneSnitchApp).container
        val force = inputData.getBoolean(KEY_FORCE, false)
        return try {
            container.classifyApps(force)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Classification failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val TAG = "ClassifyWorker"
        private const val MAX_ATTEMPTS = 3
    }
}

object ClassifyScheduler {
    private const val PERIODIC_NAME = "classify-daily"
    private const val ONCE_NAME = "classify-once"

    private val needsNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ClassifyWorker>(24, TimeUnit.HOURS)
            .setConstraints(needsNetwork)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<ClassifyWorker>()
            .setConstraints(needsNetwork)
            .setInputData(workDataOf(ClassifyWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE_NAME,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
