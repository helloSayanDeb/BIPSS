package com.bipss.app.data.sharing

import android.content.Context
import android.util.Log
import androidx.work.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Hourly background worker that uploads health data to Firestore when the
 * user has opted in to data sharing.
 *
 * Harmless when not needed: if the toggle is off or the user never opted in,
 * [DataSharingRepository.uploadIfEnabled] returns [DataSharingRepository.UploadResult.UserOptedOut]
 * and the worker finishes successfully without touching the network.
 *
 * Only [DataSharingRepository.UploadResult.Failed] triggers a retry with
 * exponential backoff. All other states (including UserOptedOut) are treated
 * as successful completions.
 */
class DataSharingWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val dataSharingRepo: DataSharingRepository by inject()

    override suspend fun doWork(): Result {
        val result = dataSharingRepo.uploadIfEnabled()
        return when (result) {
            is DataSharingRepository.UploadResult.Success -> {
                Log.i(TAG, "Data sharing upload succeeded")
                Result.success()
            }
            is DataSharingRepository.UploadResult.UserOptedOut -> {
                // Not a failure — user simply hasn't opted in. Don't retry.
                Result.success()
            }
            is DataSharingRepository.UploadResult.Failed -> {
                Log.w(TAG, "Data sharing upload failed: ${result.reason}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "DataSharingWorker"
        private const val WORK_NAME = "data_sharing_sync"

        /**
         * Enqueues an hourly periodic sync, constrained to connected network.
         *
         * Called once from [com.bipss.app.BipssApplication.onCreate]. Uses
         * [ExistingPeriodicWorkPolicy.KEEP] so re-launches don't reset the
         * schedule.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DataSharingWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
