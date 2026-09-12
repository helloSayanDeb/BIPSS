package com.bipss.app.data

import android.util.Log
import com.bipss.app.data.db.dao.DesaturationEventDao
import com.bipss.app.data.db.dao.RawReadingDao
import com.bipss.app.data.db.dao.SleepSessionDao
import com.bipss.app.data.db.entity.DesaturationEventEntity
import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.domain.sleep.ApneaScreening
import com.bipss.app.domain.sleep.NightScreening
import com.bipss.app.domain.sleep.Sample
import com.bipss.app.domain.sleep.SleepDetector

private const val TAG = "SleepRepository"

/**
 * Runs the overnight pipeline and persists what it finds.
 *
 * Detection is deliberately idempotent: a session is keyed on its start time
 * and re-running the analysis for the same night overwrites rather than
 * duplicates. The worker can therefore be retried freely without inflating the
 * night count, which would in turn inflate the screening progress indicator.
 */
class SleepRepository(
    private val rawDao:        RawReadingDao,
    private val sleepDao:      SleepSessionDao,
    private val desatDao:      DesaturationEventDao,
    private val sensorRepo:    SensorRepository
) {
    /**
     * Analyses the window ending now and covering [lookbackMs].
     *
     * Returns the detected session, or null when there was not enough data - a
     * night the band was not worn produces nothing rather than an empty session
     * that would drag the averages down.
     */
    suspend fun analyseLastNight(lookbackMs: Long = 20 * 3_600_000L): SleepSessionEntity? {
        val now = System.currentTimeMillis()
        val start = now - lookbackMs

        val accel = rawDao.getRange(MetricTypes.ACCEL_MAG, start, now)
            .map { Sample(it.timestampMs, it.value.toFloat()) }
        val hr = rawDao.getRange(MetricTypes.HR, start, now)
            .map { Sample(it.timestampMs, it.value.toFloat()) }
        val spo2 = rawDao.getRange(MetricTypes.SPO2, start, now)
            .map { Sample(it.timestampMs, it.value.toFloat()) }

        if (accel.isEmpty() && hr.isEmpty()) {
            Log.i(TAG, "No overnight data to analyse")
            return null
        }

        val restingHr = sensorRepo.restingHeartRate()
        val detected = SleepDetector.detect(accel, hr, restingHr) ?: run {
            Log.i(TAG, "No sleep session detected in the window")
            return null
        }

        val spo2InSession = spo2.filter { it.timestampMs in detected.startMs..detected.endMs }
        val entity = SleepSessionEntity(
            startMs          = detected.startMs,
            endMs            = detected.endMs,
            totalSleepMs     = detected.totalSleepMs,
            efficiencyPct    = detected.efficiencyPct,
            wakeAfterOnsetMs = detected.wakeAfterOnsetMs,
            awakenings       = detected.awakenings,
            avgHr            = detected.avgHr,
            minHr            = detected.minHr,
            avgSpo2          = spo2InSession.takeIf { it.isNotEmpty() }
                                   ?.map { it.value }?.average()?.toFloat()
        )
        val sessionId = sleepDao.insert(entity)

        // Breathing screening, if there is any SpO2 at all. Absence of SpO2
        // means no screening for that night, not a night with zero events.
        if (spo2InSession.isNotEmpty()) {
            val allEvents = ApneaScreening.detectDesaturations(spo2InSession, accel)
            desatDao.insertAll(
                allEvents.map {
                    DesaturationEventEntity(
                        sessionId      = sessionId,
                        startMs        = it.startMs,
                        durationMs     = it.durationMs,
                        baselineSpo2   = it.baselineSpo2,
                        nadirSpo2      = it.nadirSpo2,
                        dropPercent    = it.dropPercent,
                        motionArtefact = it.motionArtefact
                    )
                }
            )
            Log.i(TAG, "Night analysed: ${allEvents.size} candidate events")
        }

        return entity.copy(id = sessionId)
    }

    /** Per-night screening rows, oldest first, for the pill carousel. */
    suspend fun nightlyScreenings(): List<NightScreening> =
        sleepDao.all().map { session ->
            val valid = desatDao.validCountForSession(session.id)
            val all = desatDao.forSession(session.id).size
            NightScreening(
                sessionStartMs        = session.startMs,
                sessionEndMs          = session.endMs,
                eventCount            = valid,
                rejectedArtefactCount = all - valid,
                odi                   = ApneaScreening.computeOdi(valid, session.totalSleepMs)
            )
        }

    suspend fun lastNight(): SleepSessionEntity? = sleepDao.mostRecent()

    suspend fun nightCount(): Int = sleepDao.count()

    suspend fun sessionsNewestLast(): List<SleepSessionEntity> = sleepDao.all()

    /**
     * A night-by-night table for a clinician.
     *
     * This is the actual clinical value of the screening: not a verdict, but an
     * organised record a doctor can read in ten seconds and decide whether a
     * real sleep study is warranted. Every caveat that appears in the app
     * appears here too, because the document will be read without the app
     * beside it.
     */
    suspend fun buildPhysicianReport(): String {
        val sessions = sleepDao.all()
        val screenings = nightlyScreenings()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.UK)

        return buildString {
            appendLine("BIPSS overnight breathing record")
            appendLine("Generated ${fmt.format(java.util.Date())}")
            appendLine()
            appendLine("WHAT THIS IS")
            appendLine("A record of oxygen desaturation events counted by a consumer wrist-worn")
            appendLine("pulse oximeter during detected sleep. This is not a diagnosis and this")
            appendLine("device is not a medical device. Events coinciding with wrist movement are")
            appendLine("excluded as probable artefact; the count of those exclusions is shown so")
            appendLine("the signal quality of each night can be judged.")
            appendLine()
            appendLine("ODI = qualifying desaturation events per hour of detected sleep.")
            appendLine("A qualifying event is a drop of at least ${ApneaScreening.MIN_DROP_PERCENT.toInt()}% from a two-minute")
            appendLine("moving baseline, lasting at least ${ApneaScreening.MIN_EVENT_MS / 1000}s, without coincident motion.")
            appendLine()
            appendLine("Date            Sleep start       Sleep end         TST      Eff.  Events  Rejected  ODI")
            appendLine("-".repeat(100))

            sessions.forEachIndexed { i, s ->
                val sc = screenings.getOrNull(i)
                val tst = "${s.totalSleepMs / 3_600_000}h ${(s.totalSleepMs % 3_600_000) / 60_000}m"
                appendLine(
                    fmt.format(java.util.Date(s.startMs)).padEnd(16) +
                        fmt.format(java.util.Date(s.startMs)).padEnd(18) +
                        fmt.format(java.util.Date(s.endMs)).padEnd(18) +
                        tst.padEnd(9) +
                        "${s.efficiencyPct}%".padEnd(6) +
                        (sc?.eventCount?.toString() ?: "-").padEnd(8) +
                        (sc?.rejectedArtefactCount?.toString() ?: "-").padEnd(10) +
                        (sc?.let { String.format("%.1f", it.odi) } ?: "-")
                )
            }

            appendLine()
            if (screenings.size < ApneaScreening.NIGHTS_REQUIRED) {
                appendLine(
                    "Nights recorded: ${screenings.size} of ${ApneaScreening.NIGHTS_REQUIRED} " +
                        "considered the minimum for a stable average. No aggregate figure is given below that."
                )
            } else {
                appendLine(ApneaScreening.screeningSummary(screenings))
            }
        }
    }

    /**
     * A 0-100 sleep quality score for the Readiness pillar, or null when there
     * is no session. Duration and efficiency are weighted 60/40; a night that
     * was efficient but far too short should not score well.
     */
    suspend fun lastNightScore(): Int? {
        val s = sleepDao.mostRecent() ?: return null
        val hours = s.totalSleepMs / 3_600_000f
        val durationScore = (hours / 8f).coerceIn(0f, 1f) * 60f
        val efficiencyScore = (s.efficiencyPct / 100f).coerceIn(0f, 1f) * 40f
        return (durationScore + efficiencyScore).toInt().coerceIn(0, 100)
    }
}
