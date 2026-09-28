package com.bipss.app.data.sharing

import android.util.Log
import com.bipss.app.BuildConfig
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.db.dao.BaselineStatsDao
import com.bipss.app.data.db.dao.DesaturationEventDao
import com.bipss.app.data.db.dao.MinuteAggregateDao
import com.bipss.app.data.db.dao.SleepSessionDao
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Reads from Room and writes to Firestore in batches of up to 500 documents
 * (Firestore's batch limit). Each data type has its own watermark so partial
 * failures in one type don't block the others.
 *
 * Document IDs are deterministic (metric_minute, startMs, etc.) so a retry
 * after a crash produces idempotent writes, not duplicates.
 */
class UserDataExporter(
    private val aggDao:      MinuteAggregateDao,
    private val sleepDao:    SleepSessionDao,
    private val desatDao:    DesaturationEventDao,
    private val baselineDao: BaselineStatsDao,
    private val prefsRepo:   UserPreferencesRepository,
    private val firestore:   FirebaseFirestore
) {

    companion object {
        private const val TAG = "DataExporter"
        /** Firestore batch write limit. */
        private const val BATCH_SIZE = 500
    }

    /**
     * Exports all new data since the last sync to the given user's Firestore
     * subcollections. Each type is synced independently; a failure in one
     * does not roll back the others.
     */
    /**
     * Removes shared documents known to hold data that was never a real
     * measurement (see LegacyDataCleanup). Runs whether or not sharing is
     * currently switched on - opting out must not leave fabricated data behind.
     */
    suspend fun runMaintenance(firebaseUid: String) {
        val userRef = firestore.collection("users").document(firebaseUid)

        val pendingDeletes = prefsRepo.pendingRemoteDocDeletes.first()
        if (pendingDeletes.isNotEmpty()) {
            pendingDeletes.chunked(BATCH_SIZE).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { path -> batch.delete(userRef.collection(path.substringBefore('/')).document(path.substringAfter('/'))) }
                batch.commit().await()
                prefsRepo.removePendingRemoteDocDeletes(chunk)
            }
            Log.i(TAG, "Deleted ${pendingDeletes.size} stale shared document(s)")
        }

        if (prefsRepo.fabricatedDocSweepPending.first()) {
            // Equality-only filters, so no composite index is needed.
            val found = userRef.collection("minute_aggregates")
                .whereEqualTo("metricType", "HR")
                .whereEqualTo("avg", 100.0)
                .whereEqualTo("min", 80.0)
                .whereEqualTo("max", 120.0)
                .whereEqualTo("sampleCount", 60)
                .get().await()
            if (!found.isEmpty) {
                val batch = firestore.batch()
                found.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
                found.documents.mapNotNull { it.getLong("minuteEpoch") }.minOrNull()
                    ?.let { com.bipss.app.data.rewindSharedAggWatermarkBefore(prefsRepo, it) }
                Log.i(TAG, "Removed ${found.size()} fabricated shared document(s)")
            }
            prefsRepo.setFabricatedDocSweepPending(false)
        }
    }

    suspend fun exportAndUpload(firebaseUid: String) {
        val userRef = firestore.collection("users").document(firebaseUid)

        // 1. Minute aggregates — watermark is minuteEpoch
        val lastAggMinute = prefsRepo.lastSyncedAggMinute.first()
        val newAggs = aggDao.getRange("HR", lastAggMinute + 1, Long.MAX_VALUE) +
                      aggDao.getRange("SPO2", lastAggMinute + 1, Long.MAX_VALUE) +
                      aggDao.getRange("SKIN_TEMP", lastAggMinute + 1, Long.MAX_VALUE) +
                      aggDao.getRange("HRV", lastAggMinute + 1, Long.MAX_VALUE) +
                      aggDao.getRange("AMB_TEMP", lastAggMinute + 1, Long.MAX_VALUE) +
                      aggDao.getRange("HUMIDITY", lastAggMinute + 1, Long.MAX_VALUE) +
                      aggDao.getRange("AQI", lastAggMinute + 1, Long.MAX_VALUE) +
                      aggDao.getRange("ACCEL_MAG", lastAggMinute + 1, Long.MAX_VALUE)

        if (newAggs.isNotEmpty()) {
            val aggCollection = userRef.collection("minute_aggregates")
            var highWater = lastAggMinute

            newAggs.chunked(BATCH_SIZE).forEach { chunk ->
                val batch: WriteBatch = firestore.batch()
                chunk.forEach { agg ->
                    val docId = "${agg.metricType}_${agg.minuteEpoch}"
                    val doc = aggCollection.document(docId)
                    batch.set(doc, mapOf(
                        "metricType"  to agg.metricType,
                        "minuteEpoch" to agg.minuteEpoch,
                        "avg"         to agg.avg,
                        "min"         to agg.min,
                        "max"         to agg.max,
                        "sampleCount" to agg.sampleCount
                    ))
                    if (agg.minuteEpoch > highWater) highWater = agg.minuteEpoch
                }
                batch.commit().await()
            }
            prefsRepo.setLastSyncedAggMinute(highWater)
            Log.i(TAG, "Synced ${newAggs.size} minute aggregates up to minute $highWater")
        }

        // 2. Sleep sessions — watermark is startMs
        val lastSleepMs = prefsRepo.lastSyncedSleepMs.first()
        val newSleep = sleepDao.since(lastSleepMs + 1)

        if (newSleep.isNotEmpty()) {
            val sleepCollection = userRef.collection("sleep_sessions")
            var highWater = lastSleepMs

            newSleep.chunked(BATCH_SIZE).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { session ->
                    val doc = sleepCollection.document(session.startMs.toString())
                    batch.set(doc, mapOf(
                        "startMs"          to session.startMs,
                        "endMs"            to session.endMs,
                        "totalSleepMs"     to session.totalSleepMs,
                        "efficiencyPct"    to session.efficiencyPct,
                        "wakeAfterOnsetMs" to session.wakeAfterOnsetMs,
                        "awakenings"       to session.awakenings,
                        "avgHr"            to session.avgHr,
                        "minHr"            to session.minHr,
                        "avgSpo2"          to session.avgSpo2
                    ))
                    if (session.startMs > highWater) highWater = session.startMs
                }
                batch.commit().await()
            }
            prefsRepo.setLastSyncedSleepMs(highWater)
            Log.i(TAG, "Synced ${newSleep.size} sleep sessions up to $highWater")

            // 3. Desaturation events for the new sessions
            var desatHighWater = prefsRepo.lastSyncedDesatMs.first()
            val desatCollection = userRef.collection("desaturation_events")

            for (session in newSleep) {
                val events = desatDao.forSession(session.id)
                if (events.isEmpty()) continue

                events.chunked(BATCH_SIZE).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { event ->
                        val docId = "${event.sessionId}_${event.startMs}"
                        val doc = desatCollection.document(docId)
                        batch.set(doc, mapOf(
                            "sessionId"     to event.sessionId,
                            "startMs"       to event.startMs,
                            "durationMs"    to event.durationMs,
                            "baselineSpo2"  to event.baselineSpo2,
                            "nadirSpo2"     to event.nadirSpo2,
                            "dropPercent"   to event.dropPercent,
                            "motionArtefact" to event.motionArtefact
                        ))
                        if (event.startMs > desatHighWater) desatHighWater = event.startMs
                    }
                    batch.commit().await()
                }
            }
            prefsRepo.setLastSyncedDesatMs(desatHighWater)
        }

        // 4. Baseline stats — always upserted (small, 3-6 docs), no watermark needed
        val baselines = baselineDao.all().first()
        if (baselines.isNotEmpty()) {
            val baselineCollection = userRef.collection("baseline_stats")
            val batch = firestore.batch()
            baselines.forEach { bl ->
                val doc = baselineCollection.document(bl.metricType)
                batch.set(doc, mapOf(
                    "metricType" to bl.metricType,
                    "mean"       to bl.mean,
                    "m2"         to bl.m2,
                    "count"      to bl.count,
                    "stdDev"     to bl.stdDev
                ))
            }
            batch.commit().await()
            Log.i(TAG, "Synced ${baselines.size} baseline stats")
        }

        // Update profile metadata
        userRef.set(mapOf(
            "sharingUsername" to prefsRepo.getOrCreateSharingUsername(),
            "lastSyncAtMs"   to System.currentTimeMillis(),
            "appVersion"     to BuildConfig.VERSION_NAME
        ), com.google.firebase.firestore.SetOptions.merge()).await()
    }
}
