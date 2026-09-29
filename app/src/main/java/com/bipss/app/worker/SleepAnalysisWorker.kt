package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.data.SleepRepository
import com.bipss.app.domain.sleep.DesaturationScreening
import com.bipss.app.notifications.BipssNotifications
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Runs sleep detection and the oxygen-dip count once a day.
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
    private val scoring: com.bipss.app.data.ScoringRepository by inject()
    private val legacyCleanup: com.bipss.app.data.LegacyDataCleanup by inject()

    override suspend fun doWork(): Result = try {
        // Never touch these tables while a one-time legacy repair is running.
        legacyCleanup.awaitReady()
        val session = sleepRepo.analyseLastNight()
        // Fold the new night into the recovery baselines exactly once.
        runCatching { scoring.processNewNights() }
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
                BipssNotifications.showBreathingSummary(
                    applicationContext,
                    eventCount     = lastNight.eventCount,
                    nightsCollected = nights,
                    nightsRequired = DesaturationScreening.NIGHTS_REQUIRED
                )
            }
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        /** Previously scheduled with no initial delay, so it ran at whatever hour the app was installed. */
        private const val OLD_WORK_NAME = "sleep_analysis"
        private const val WORK_NAME = "sleep_analysis_daily"
        private const val RUN_AT_HOUR = 10

        /**
         * Daily at about 10:00 local time: the night is over for most people,
         * and its raw samples are still un-aggregated. The old, unanchored
         * schedule is replaced once (a new unique name, the old one cancelled).
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(OLD_WORK_NAME)
            val now = java.time.ZonedDateTime.now()
            var next = now.withHour(RUN_AT_HOUR).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delayMs = java.time.Duration.between(now, next).toMillis()
            val request = PeriodicWorkRequestBuilder<SleepAnalysisWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
