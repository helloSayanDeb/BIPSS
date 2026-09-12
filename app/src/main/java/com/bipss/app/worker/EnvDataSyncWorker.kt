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
 * Fetches environmental data every three hours and notifies on new severe alerts.
 *
 * De-duplication against previously notified identifiers is what makes this
 * usable: without it the same rainfall warning would fire a fresh notification
 * every three hours for as long as it stayed active, and users would silence
 * the channel within a day.
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

        /** A burst of notifications is worse than none - cap it. */
        private const val MAX_NOTIFICATIONS_PER_RUN = 3

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EnvDataSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
