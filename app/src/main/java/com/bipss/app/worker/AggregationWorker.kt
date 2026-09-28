package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.entity.MinuteAggregateEntity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Rolls raw 1 Hz samples older than seven days into minute averages, then
 * prunes them.
 *
 * Without this the history views are impossible: at 1 Hz across six metrics,
 * ninety days is on the order of forty million rows. Keeping raw for a week and
 * minute aggregates for ninety days gives the Day view full resolution and the
 * Week and Month views a dataset small enough to query instantly.
 *
 * Work is done in bounded batches so a device that has been offline for a long
 * time cannot try to load a million rows into memory at once.
 */
class AggregationWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val db: AppDatabase by inject()
    private val legacyCleanup: com.bipss.app.data.LegacyDataCleanup by inject()

    override suspend fun doWork(): Result = try {
        // Never touch these tables while a one-time legacy repair is running.
        legacyCleanup.awaitReady()
        val rawDao = db.rawReadingDao()
        val aggDao = db.minuteAggregateDao()

        val rawCutoff = System.currentTimeMillis() - RAW_RETENTION_DAYS * 86_400_000L

        var batches = 0
        while (batches < MAX_BATCHES_PER_RUN) {
            val old = rawDao.getOlderThan(rawCutoff, BATCH_SIZE)
            if (old.isEmpty()) break

            val rows = old
                .groupBy { it.metricType to (it.timestampMs / 60_000L) }
                .map { (key, samples) ->
                    val values = samples.map { it.value.toFloat() }
                    MinuteAggregateEntity(
                        metricType  = key.first,
                        minuteEpoch = key.second,
                        avg         = values.average().toFloat(),
                        min         = values.min(),
                        max         = values.max(),
                        sampleCount = values.size
                    )
                }
            aggDao.upsertAll(rows)

            // Only safe because everything in this batch is now aggregated.
            val highWater = old.maxOf { it.timestampMs }
            rawDao.pruneOlderThan(highWater + 1)
            batches++
        }

        // Aggregates themselves expire, keeping the database bounded.
        val aggCutoff = (System.currentTimeMillis() - AGGREGATE_RETENTION_DAYS * 86_400_000L) / 60_000L
        aggDao.pruneOlderThan(aggCutoff)

        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        private const val WORK_NAME = "raw_reading_aggregation"

        const val RAW_RETENTION_DAYS = 7L
        const val AGGREGATE_RETENTION_DAYS = 90L

        private const val BATCH_SIZE = 20_000
        private const val MAX_BATCHES_PER_RUN = 20

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AggregationWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
