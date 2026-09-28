package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.data.SleepRepository
import com.bipss.app.domain.sleep.ApneaScreening
import com.bipss.app.notifications.BipssNotifications
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Runs sleep detection and breathing screening once a day.
 *
 * Scheduled rather than triggered on wake because wake detection is itself an
 * output of the analysis. Running mid-morning means the whole night is present
 * in the database and the raw samples have not yet been rolled into minute
 * aggregates, which would blur the 30-second epochs the detector needs.
 */
class SleepAnalysisWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val sleepRepo: SleepRepository by inject()
    private val legacyCleanup: com.bipss.app.data.LegacyDataCleanup by inject()

    override suspend fun doWork(): Result = try {
        // Never touch these tables while a one-time legacy repair is running.
        legacyCleanup.awaitReady()
        val session = sleepRepo.analyseLastNight()
        if (session != null) {
            val nights = sleepRepo.nightCount()
            val screenings = sleepRepo.nightlyScreenings()
            val lastNight = screenings.lastOrNull()

            // The briefing is ready as soon as a night has been analysed, which
            // is the moment it is worth reading.
            val hours = session.totalSleepMs / 3_600_000
            val mins = (session.totalSleepMs % 3_600_000) / 60_000
            BipssNotifications.showCoaching(
                applicationContext,
                title = "Good morning",
                body  = "You slept ${hours}h ${mins}m. Your briefing is ready."
            )

            if (lastNight != null && lastNight.eventCount > 0) {
                BipssNotifications.showApneaSummary(
                    applicationContext,
                    eventCount     = lastNight.eventCount,
                    nightsCollected = nights,
                    nightsRequired = ApneaScreening.NIGHTS_REQUIRED
                )
            }
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        private const val WORK_NAME = "sleep_analysis"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SleepAnalysisWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
