package com.bipss.app.data

import android.util.Log
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.worker.AggregationWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "LegacyDataCleanup"

/**
 * The fake row at minute F was uploaded at press time, moving the shared-data
 * watermark to F. Aggregates only exist for data a week old, so the real
 * aggregates for the week before F were created afterwards and skipped.
 * Rewinding to just before that week (never forwards) re-uploads exactly that
 * window; uploads are idempotent by document ID.
 */
suspend fun rewindSharedAggWatermarkBefore(prefs: UserPreferencesRepository, fabricatedMinute: Long) {
    val target = (fabricatedMinute - AggregationWorker.RAW_RETENTION_DAYS * 1_440L - 60L).coerceAtLeast(0L)
    if (prefs.lastSyncedAggMinute.first() > target) prefs.setLastSyncedAggMinute(target)
}

/**
 * One-time repairs for data written by code that no longer exists.
 *
 * The first two repairs remove data that was never a measurement of the user;
 * the third restores real measurements a removed rule had hidden:
 *
 *  - **Demo Mode** replayed bench-test captures straight into `raw_readings`,
 *    the minute aggregates, the Welford baselines and sleep analysis, stamped
 *    with arrival time and no source column. Once it has been switched on, the
 *    real and the replayed rows cannot be told apart, so every sensor-derived
 *    table is cleared and calibration restarts from Day 1. Profile, contacts,
 *    settings and SOS history are untouched.
 *  - The **"Force Firebase Upload"** test button wrote a fabricated
 *    `HR avg 100 / min 80 / max 120 / n 60` minute into the user's history and
 *    uploaded it. That row is deleted locally, queued for deletion from the
 *    sharing store (plus a one-time remote sweep in case the local row is
 *    already gone), and the upload watermark it pushed forward is rewound to
 *    just before the window it made the exporter skip.
 *  - The removed **3-sigma anomaly gate** flagged exercise heart rate; those
 *    flags are re-evaluated once against the physiological bounds.
 *
 * Each repair is recorded as done only after its destructive step succeeded,
 * so a crash mid-way is retried on the next start. Workers that write to the
 * same tables call [awaitReady] first, so none of them can run concurrently
 * with a wipe and write old data back.
 */
class LegacyDataCleanup(
    private val db:    AppDatabase,
    private val prefs: UserPreferencesRepository
) {
    private val ready = CompletableDeferred<Unit>()

    /** Suspends until [run] has finished (bounded, so a stuck repair cannot block workers forever). */
    suspend fun awaitReady() {
        withTimeoutOrNull(60_000L) { ready.await() }
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        try {
            if (prefs.hasLegacyDemoMarker()) wipeSensorHistory()
            if (!prefs.isLegacyCleanupV1Done()) {
                removeFabricatedTestAggregate()
                prefs.markLegacyCleanupV1Done()
            }
            if (!prefs.isAnomalyReflagDone()) {
                reflagAnomaliesByBounds()
                prefs.markAnomalyReflagDone()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy clean-up failed - will retry next start", e)
        } finally {
            ready.complete(Unit)
        }
    }

    private suspend fun wipeSensorHistory() {
        val sql = db.openHelper.writableDatabase
        db.runInTransaction {
            sql.execSQL("DELETE FROM raw_readings")
            sql.execSQL("DELETE FROM minute_aggregates")
            sql.execSQL("DELETE FROM baseline_stats")
            sql.execSQL("DELETE FROM desaturation_events")
            sql.execSQL("DELETE FROM sleep_sessions")
        }
        prefs.clearFirstReadingMs()
        // Anything uploaded while Demo Mode was on is equally untrustworthy.
        if (prefs.sharingFirebaseUid.first() != null) prefs.requestRemotePurge()
        prefs.completeLegacyDemoWipe(System.currentTimeMillis())
        Log.i(TAG, "Demo Mode had been used - sensor history wiped, calibration restarts")
    }

    private suspend fun removeFabricatedTestAggregate() {
        // The local row may already have been overwritten by a real aggregate
        // for the same minute, so the sharing store is swept remotely as well.
        if (prefs.sharingFirebaseUid.first() != null) prefs.setFabricatedDocSweepPending(true)

        val sql = db.openHelper.writableDatabase
        val minutes = mutableListOf<Long>()
        sql.query("SELECT minuteEpoch FROM minute_aggregates WHERE $FABRICATED_ROW").use { c ->
            while (c.moveToNext()) minutes += c.getLong(0)
        }
        if (minutes.isEmpty()) return

        // Idempotent steps first, destructive local delete last.
        prefs.addPendingRemoteDocDeletes(minutes.map { "minute_aggregates/HR_$it" })
        rewindWatermarkBefore(minutes.min())
        sql.execSQL("DELETE FROM minute_aggregates WHERE $FABRICATED_ROW")
        Log.i(TAG, "Removed ${minutes.size} fabricated test aggregate row(s)")
    }

    /**
     * The removed 3-sigma gate flagged samples by distance from an all-time
     * mean, which marked ordinary exercise heart rate as anomalous. Those rows
     * are now excluded from charts and aggregates, so they are re-judged once
     * by the physiological bounds alone - before aggregation, which waits on
     * [awaitReady], can roll them up without them.
     */
    private fun reflagAnomaliesByBounds() {
        val sql = db.openHelper.writableDatabase
        val inBounds = SensorRepository.SANITY_BOUNDS.entries.joinToString(" OR ") { (type, range) ->
            "(metricType = '$type' AND value BETWEEN ${range.start} AND ${range.endInclusive})"
        }
        sql.execSQL("UPDATE raw_readings SET isAnomalous = 0 WHERE isAnomalous = 1 AND ($inBounds)")
        Log.i(TAG, "Re-evaluated legacy anomaly flags against physiological bounds")
    }

    private suspend fun rewindWatermarkBefore(fabricatedMinute: Long) =
        rewindSharedAggWatermarkBefore(prefs, fabricatedMinute)

    companion object {
        const val FABRICATED_ROW =
            "metricType = 'HR' AND avg = 100 AND min = 80 AND max = 120 AND sampleCount = 60"
    }
}
