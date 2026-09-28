package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.notifications.BipssNotifications
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Fetches environmental data every 15 minutes (WorkManager's minimum periodic
 * interval) and notifies on new severe alerts.
 *
 * De-duplication against previously notified identifiers is what makes this
 * usable: without it the same rainfall warning would fire a fresh notification
 * on every run for as long as it stayed active, and users would silence the
 * channel within a day.
 */
class EnvDataSyncWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val repository: DisasterAlertRepository by inject()
    private val prefsRepo:  UserPreferencesRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            val snapshot = repository.fetchAndCache()

            snapshot.activeAlerts
                .filter { it.severity.rank >= AlertSeverity.WARNING.rank }
                .filterNot { prefsRepo.hasNotifiedAlert(it.identifier) }
                .take(MAX_NOTIFICATIONS_PER_RUN)
                .forEach { alert ->
                    BipssNotifications.showAlert(applicationContext, alert, personalNote = null)
                    prefsRepo.markAlertNotified(alert.identifier)
                }

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "env_data_sync"

        /** WorkManager does not run periodic work more often than this. */
        const val SYNC_INTERVAL_MINUTES = 15L

        /** A burst of notifications is worse than none - cap it. */
        private const val MAX_NOTIFICATIONS_PER_RUN = 3

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EnvDataSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            // UPDATE, not KEEP: KEEP would leave existing installs on the old
            // hourly schedule forever.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
