package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.data.InsightsRefresher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Keeps the derived figures (daily summary, detected workouts, VO2 max,
 * BioAge) current while the app is closed, so a morning look needs no wait
 * and a day is summarised before its raw readings age out.
 */
class InsightsRefreshWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val refresher: InsightsRefresher by inject()
    private val legacyCleanup: com.bipss.app.data.LegacyDataCleanup by inject()

    override suspend fun doWork(): Result = try {
        legacyCleanup.awaitReady()
        refresher.refresh(force = true)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        private const val WORK_NAME = "insights_refresh"

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InsightsRefreshWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
