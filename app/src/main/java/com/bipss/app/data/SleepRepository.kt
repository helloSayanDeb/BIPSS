package com.bipss.app.data

import android.util.Log
import androidx.room.withTransaction
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.dao.DesaturationEventDao
import com.bipss.app.data.db.dao.RawReadingDao
import com.bipss.app.data.db.dao.SignalEpochDao
import com.bipss.app.data.db.dao.SleepSessionDao
import com.bipss.app.data.db.entity.DesaturationEventEntity
import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.domain.recovery.RecoveryInput
import com.bipss.app.domain.signal.SignalQuality
import com.bipss.app.domain.sleep.DesaturationScreening
import com.bipss.app.domain.sleep.MinuteEpoch
import com.bipss.app.domain.sleep.NightScreening
import com.bipss.app.domain.sleep.Sample
import com.bipss.app.domain.sleep.SleepDetector

private const val TAG = "SleepRepository"

/**
 * Runs the overnight analysis and persists what it finds.
 *
 * Detection is idempotent: re-running it for the same night replaces the
 * overlapping session and its events, so the worker can be retried without
 * inflating the night count.
 */
class SleepRepository(
    private val rawDao:   RawReadingDao,
    private val sleepDao: SleepSessionDao,
    private val desatDao: DesaturationEventDao,
    private val epochDao: SignalEpochDao,
    private val scoring:  ScoringRepository,
    private val db: AppDatabase
) {
    /**
     * Analyses the window ending now and covering [lookbackMs]. Returns the
     * detected session, or null when there was none - a night without the band
     * produces nothing rather than an empty session that drags averages down.
     */
    suspend fun analyseLastNight(lookbackMs: Long = 20 * 3_600_000L): SleepSessionEntity? {
        val now = System.currentTimeMillis()
        val start = (now - lookbackMs) / SleepDetector.EPOCH_MS * SleepDetector.EPOCH_MS

        val activity = rawDao.getBuckets(MetricTypes.ACTIVITY, start, now, SleepDetector.EPOCH_MS)
            .associateBy { it.bucketStartMs }
        if (activity.isEmpty()) {
            // A v1 band sends no movement data; sleep cannot be detected from it.
            Log.i(TAG, "No movement data in the window - nothing to analyse")
            return null
        }
        val hr = rawDao.getBuckets(MetricTypes.HR, start, now, SleepDetector.EPOCH_MS).associateBy { it.bucketStartMs }
        // Signal quality per minute from the pipeline's 30 s epochs (v2 bands).
        val quality = epochDao.range(start, now).groupBy { it.startMs / SleepDetector.EPOCH_MS * SleepDetector.EPOCH_MS }
            .mapValues { (_, es) -> es.map { it.quality } }

        val epochs = (start until now step SleepDetector.EPOCH_MS).map { m ->
            val a = activity[m]
            val q = quality[m]
            MinuteEpoch(
                startMs  = m,
                activity = a?.avgValue?.let { it.toDouble() * a.sampleCount },
                hr       = hr[m]?.avgValue?.toDouble(),
                // Half a minute of status packets, and not all-Poor signal.
                onWrist  = a != null && a.sampleCount >= MIN_SAMPLES_PER_MINUTE &&
                    (q == null || q.any { it != SignalQuality.POOR.name })
            )
        }

        val sleepingHr = scoring.currentBaselines()[RecoveryInput.RESTING_HR]?.mean
        val detected = SleepDetector.detect(epochs, sleepingHr, scoring.dayN()) ?: run {
            Log.i(TAG, "No sleep session detected in the window")
            return null
        }

        val spo2 = rawDao.getRange(MetricTypes.SPO2, detected.startMs, detected.endMs)
            .filter { !it.isAnomalous }
            .map { Sample(it.timestampMs, it.value.toFloat()) }
        val entity = SleepSessionEntity(
            startMs          = detected.startMs,
            endMs            = detected.endMs,
            totalSleepMs     = detected.totalSleepMs,
            efficiencyPct    = detected.efficiencyPct,
            wakeAfterOnsetMs = detected.wakeAfterOnsetMs,
            awakenings       = detected.awakenings,
            avgHr            = detected.avgHr,
            minHr            = detected.minHr,
            avgSpo2          = spo2.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.toFloat()
        )
        // Replace the session and its events together: observers never see half a night.
        val sessionId = db.withTransaction {
        val stale = sleepDao.overlappingIds(entity.startMs, entity.endMs)
        if (stale.isNotEmpty()) {
            desatDao.deleteForSessions(stale)
            sleepDao.deleteByIds(stale)
        }
        val sessionId = sleepDao.insert(entity)

        // Oxygen dips, if there is any SpO2 at all. No SpO2 means no count for
        // that night, not a night with zero dips.
        if (spo2.isNotEmpty()) {
            val moves = rawDao.getRange(
                MetricTypes.ACTIVITY,
                detected.startMs - DesaturationScreening.MOTION_GUARD_MS,
                detected.endMs + DesaturationScreening.MOTION_GUARD_MS
            ).map { Sample(it.timestampMs, it.value.toFloat()) }
            val events = DesaturationScreening.detectDesaturations(spo2, moves)
            desatDao.insertAll(events.map {
                DesaturationEventEntity(
                    sessionId      = sessionId,
                    startMs        = it.startMs,
                    durationMs     = it.durationMs,
                    baselineSpo2   = it.baselineSpo2,
                    nadirSpo2      = it.nadirSpo2,
                    dropPercent    = it.dropPercent,
                    motionArtefact = it.motionArtefact
                )
            })
            Log.i(TAG, "Night analysed: ${events.size} candidate oxygen dips")
        }
        sessionId
        }
        return entity.copy(id = sessionId)
    }

    /** Per-night rows, oldest first. */
    suspend fun nightlyScreenings(): List<NightScreening> =
        sleepDao.all().mapNotNull { session ->
            if (session.avgSpo2 == null || session.totalSleepMs <= 0L) return@mapNotNull null
            val valid = desatDao.validCountForSession(session.id)
            val all = desatDao.forSession(session.id).size
            recordedScreening(session, valid, all - valid)
        }

    suspend fun lastNight(): SleepSessionEntity? = sleepDao.mostRecent()

    suspend fun nightCount(): Int = sleepDao.count()

    suspend fun sessionsNewestLast(): List<SleepSessionEntity> = sleepDao.all()

    /**
     * A night-by-night record a user can hand to a clinician, with every dip -
     * counted or excluded - listed underneath. Every caveat shown in the app
     * is repeated, because the document is read without the app beside it.
     */
    suspend fun buildPhysicianReport(): String {
        val sessions = sleepDao.all()
        val screenings = nightlyScreenings()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.UK)
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
        val secFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK)

        return buildString {
            appendLine("BIPSS overnight oxygen record")
            appendLine("Generated ${fmt.format(java.util.Date())}")
            appendLine()
            appendLine("WHAT THIS IS")
            appendLine("Counts of drops in blood oxygen (SpO2) measured by a consumer wrist-worn")
            appendLine("band during sleep detected from wrist movement. This is not a diagnosis and")
            appendLine("the band is not a medical device. Its SpO2 uses an uncalibrated ratio curve,")
            appendLine("and its sleep detection is an estimate. Drops within 15 s of wrist movement")
            appendLine("are listed but not counted, as probable movement artefact.")
            appendLine()
            appendLine("ODI = counted drops per hour of detected sleep. A counted drop is a fall of at")
            appendLine("least ${DesaturationScreening.MIN_DROP_PERCENT.toInt()} points below the trailing two-minute mean, lasting at least")
            appendLine("${DesaturationScreening.MIN_EVENT_MS / 1000} s, with no wrist movement within 15 s.")
            appendLine()
            appendLine("Date        Start  End    TST      Eff.  Counted  Excluded  ODI")
            appendLine("-".repeat(64))
            sessions.forEach { s ->
                val sc = screenings.firstOrNull { it.sessionStartMs == s.startMs && it.sessionEndMs == s.endMs }
                val tst = "${s.totalSleepMs / 3_600_000}h ${(s.totalSleepMs % 3_600_000) / 60_000}m"
                appendLine(
                    dayFmt.format(java.util.Date(s.startMs)).padEnd(12) +
                        timeFmt.format(java.util.Date(s.startMs)).padEnd(7) +
                        timeFmt.format(java.util.Date(s.endMs)).padEnd(7) +
                        tst.padEnd(9) +
                        "${s.efficiencyPct}%".padEnd(6) +
                        (sc?.eventCount?.toString() ?: "-").padEnd(9) +
                        (sc?.rejectedArtefactCount?.toString() ?: "-").padEnd(10) +
                        (sc?.let { String.format("%.1f", it.odi) } ?: "-")
                )
            }
            appendLine()
            if (screenings.size < DesaturationScreening.NIGHTS_REQUIRED) {
                appendLine(
                    "Nights recorded: ${screenings.size} of ${DesaturationScreening.NIGHTS_REQUIRED} " +
                        "considered the minimum for a stable average. No average is given below that."
                )
            } else {
                appendLine(DesaturationScreening.screeningSummary(screenings))
            }

            appendLine()
            appendLine("EVERY DROP")
            appendLine("Time      Dur.  Baseline  Lowest  Drop   Status")
            appendLine("-".repeat(64))
            for (s in sessions) {
                val events = desatDao.forSession(s.id).sortedBy { it.startMs }
                appendLine("Night of ${dayFmt.format(java.util.Date(s.startMs))}: ${events.size} candidate drop(s)")
                for (e in events) {
                    val status = when {
                        e.motionArtefact -> "not counted: movement"
                        e.durationMs < DesaturationScreening.MIN_EVENT_MS -> "not counted: under ${DesaturationScreening.MIN_EVENT_MS / 1000} s"
                        else -> "counted"
                    }
                    appendLine(
                        secFmt.format(java.util.Date(e.startMs)).padEnd(10) +
                            "${e.durationMs / 1000}s".padEnd(6) +
                            String.format("%.1f%%", e.baselineSpo2).padEnd(10) +
                            String.format("%.0f%%", e.nadirSpo2).padEnd(8) +
                            String.format("%.1f", e.dropPercent).padEnd(7) +
                            status
                    )
                }
            }
        }
    }

    /**
     * A 0-100 sleep score for the Readiness pillar, or null without a session.
     * Duration and efficiency are weighted 60/40, so an efficient but far too
     * short night does not score well. An estimate, like the detection under it.
     */
    suspend fun lastNightScore(): Int? {
        val today = com.bipss.app.domain.time.LocalDay.epochDay(System.currentTimeMillis())
        val s = sleepDao.all().filter { com.bipss.app.domain.time.LocalDay.epochDay(it.endMs) == today }
            .maxByOrNull { it.totalSleepMs } ?: return null
        return score(s)
    }

    companion object {
        private const val MIN_SAMPLES_PER_MINUTE = 30
        fun score(s: SleepSessionEntity): Int {
        val hours = s.totalSleepMs / 3_600_000f
        val durationScore = (hours / 8f).coerceIn(0f, 1f) * 60f
        val efficiencyScore = (s.efficiencyPct / 100f).coerceIn(0f, 1f) * 40f
        return (durationScore + efficiencyScore).toInt().coerceIn(0, 100)
        }
    }
}
