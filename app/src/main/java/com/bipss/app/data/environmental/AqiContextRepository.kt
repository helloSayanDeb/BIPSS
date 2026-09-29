package com.bipss.app.data.environmental

import com.bipss.app.data.MetricTypes
import com.bipss.app.data.SensorRepository
import com.bipss.app.data.db.dao.AqiContextSampleDao
import com.bipss.app.data.db.dao.RawReadingDao
import com.bipss.app.data.db.entity.AqiContextSampleEntity
import com.bipss.app.domain.environmental.AqiContextSample
import com.bipss.app.domain.environmental.AqiReconciler
import com.bipss.app.domain.environmental.EnvContextClassifier
import com.bipss.app.domain.environmental.EnvContextKind
import com.bipss.app.domain.environmental.GnssSnapshot
import com.bipss.app.domain.environmental.Reconciliation
import com.bipss.app.domain.environmental.haversineKm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The indoor/outdoor checks and the AQI reconciliation built on them (spec
 * 5.9). A check is taken by the band service every 10 minutes while the band
 * is connected (see BleForegroundService); each stores the GNSS-based
 * context, its evidence, and the band and station air readings of that
 * moment. The reconciliation is rebuilt from the last 90 days of checks.
 */
class AqiContextRepository(
    private val sampler:    LocationContextSampler,
    private val dao:        AqiContextSampleDao,
    private val sensorRepo: SensorRepository,
    private val rawDao:     RawReadingDao,
    private val envRepo:    DisasterAlertRepository
) {
    private val mutex = Mutex()
    /** Last position, in memory only - for "has the user moved", never stored. */
    private var lastPosition: Triple<Double, Double, Long>? = null

    val latest: Flow<AqiContextSampleEntity?> = dao.latestFlow()

    /** Takes one check now. Null when GNSS cannot be used (see [LocationContextSampler]). */
    suspend fun sampleNow(nowMs: Long = System.currentTimeMillis()): AqiContextSampleEntity? = mutex.withLock {
        val obs = sampler.observe() ?: return null
        val displacementM = lastPosition?.let { (lat, lon, atMs) ->
            if (obs.lat == null || obs.lon == null || nowMs - atMs > MAX_DISPLACEMENT_GAP_MS) null
            else haversineKm(lat, lon, obs.lat, obs.lon) * 1000.0
        }
        if (obs.lat != null && obs.lon != null) lastPosition = Triple(obs.lat, obs.lon, nowMs)

        // Activity is stored per second (mg·s); the classifier works per minute.
        val activity = rawDao.averageBetween(MetricTypes.ACTIVITY, nowMs - ACTIVITY_WINDOW_MS, nowMs)?.times(60.0)
        val snapshot = GnssSnapshot(
            satellitesVisible = obs.satellitesVisible,
            satellitesUsed    = obs.satellitesUsed,
            topCn0DbHz        = obs.topCn0DbHz,
            accuracyM         = obs.accuracyM,
            speedMps          = obs.speedMps,
            displacementM     = displacementM,
            activityMgSPerMin = activity
        )
        val context = EnvContextClassifier.classify(snapshot)

        // The station reading of the same period; an old one says nothing about now.
        val env = envRepo.getCachedSnapshot()
        val cpcb = env?.regionalAqiValue?.takeIf { env.fetchedAtMs > 0L && nowMs - env.fetchedAtMs <= CPCB_MAX_AGE_MS }

        val row = AqiContextSampleEntity(
            takenAtMs         = nowMs,
            context           = context.kind.name,
            confidence        = context.confidence,
            evidence          = context.evidence.joinToString("\n"),
            bandUba           = sensorRepo.bandSensorAqi.value,
            cpcbAqi           = cpcb,
            satellitesVisible = obs.satellitesVisible,
            satellitesUsed    = obs.satellitesUsed,
            topCn0DbHz        = obs.topCn0DbHz,
            accuracyM         = obs.accuracyM,
            speedMps          = obs.speedMps,
            displacementM     = displacementM,
            activityMgSPerMin = activity
        )
        dao.insert(row)
        dao.deleteBefore(nowMs - KEEP_MS)
        row
    }

    suspend fun reconciliation(nowMs: Long = System.currentTimeMillis()): Reconciliation =
        AqiReconciler.build(dao.since(nowMs - KEEP_MS).map { it.toSample() })

    companion object {
        /** The baseline schedule runs to day 90. */
        const val KEEP_MS = 90L * 24 * 3_600_000L
        const val ACTIVITY_WINDOW_MS = 3 * 60_000L
        const val MAX_DISPLACEMENT_GAP_MS = 20 * 60_000L
        /** The environment sync runs every 15 minutes; allow for a missed run or two. */
        const val CPCB_MAX_AGE_MS = 90 * 60_000L

        fun AqiContextSampleEntity.toSample() = AqiContextSample(
            takenAtMs  = takenAtMs,
            kind       = runCatching { EnvContextKind.valueOf(context) }.getOrDefault(EnvContextKind.UNKNOWN),
            confidence = confidence,
            bandUba    = bandUba,
            cpcbAqi    = cpcbAqi
        )
    }
}
