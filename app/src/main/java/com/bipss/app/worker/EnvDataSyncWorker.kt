package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.data.environmental.DisasterAlertRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job — fetches environmental data every 3 hours.
 * Runs only when the device has network connectivity.
 */
class EnvDataSyncWorker(
    appContext:     Context,
    workerParams:   WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val repository: DisasterAlertRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            repository.fetchAndCache()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "env_data_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EnvDataSyncWorker>(3, TimeUnit.HOURS)
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
