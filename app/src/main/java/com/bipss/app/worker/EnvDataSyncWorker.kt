package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.notifications.BipssNotifications
import org.koin.core.component.KoinComponent
import kotlinx.coroutines.flow.first
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

    private val repository:     DisasterAlertRepository by inject()
    private val prefsRepo:      UserPreferencesRepository by inject()
    private val locationHelper: com.bipss.app.sos.LocationHelper by inject()
    private val scheduler:      WorkScheduler by inject()
    private val snapshotDao:    com.bipss.app.data.db.dao.EnvSnapshotDao by inject()

    override suspend fun doWork(): Result {
        // Permission taken away while the app was closed: stop the schedule
        // and forget what was derived from location, without a single request.
        if (!locationHelper.hasAnyPermission()) {
            scheduler.reconcile()
            return Result.success()
        }
        return try {
            val snapshot = repository.fetchAndCache()
            recordSnapshot(snapshot)

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

    /**
     * Appends a freshly fetched snapshot to the ring buffer. Only this job
     * writes it, and only for a real fetch at least MIN_SPACING_MS after the
     * previous row, so the series is evenly spaced.
     */
    private suspend fun recordSnapshot(snapshot: com.bipss.app.domain.environmental.EnvironmentalSnapshot) {
        if (snapshot.isCachedData || snapshot.fetchedAtMs == 0L) return
        val last = snapshotDao.latest()
        if (last != null && snapshot.fetchedAtMs - last.takenAtMs < com.bipss.app.data.db.dao.EnvSnapshotDao.MIN_SPACING_MS) return
        val cached = prefsRepo.cachedEnvironmentalData.first() ?: return
        // Advisories are global news, not a property of the place; left out to keep rows small.
        val json = SNAPSHOT_JSON.encodeToString(
            UserPreferencesRepository.CachedEnvData.serializer(), cached.copy(advisories = emptyList())
        )
        snapshotDao.insertAndTrim(
            com.bipss.app.data.db.entity.EnvSnapshotEntity(
                takenAtMs        = snapshot.fetchedAtMs,
                aqi              = snapshot.aqiValue,
                tempC            = snapshot.ambientTempC,
                humidityPct      = snapshot.humidityPercent,
                uvIndex          = snapshot.uvIndex,
                pressureHpa      = snapshot.pressureHpa,
                windKph          = snapshot.windSpeedKph,
                maxAlertSeverity = snapshot.activeAlerts.maxOfOrNull { it.severity.rank } ?: 0,
                json             = json
            )
        )
    }

    companion object {
        private val SNAPSHOT_JSON = kotlinx.serialization.json.Json { encodeDefaults = true }

        private const val WORK_NAME = "env_data_sync"

        /** WorkManager does not run periodic work more often than this. */
        const val SYNC_INTERVAL_MINUTES = 15L

        /** A burst of notifications is worse than none - cap it. */
        private const val MAX_NOTIFICATIONS_PER_RUN = 3

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

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
