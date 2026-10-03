package com.bipss.app.data

import androidx.room.withTransaction
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.entity.EwmaBaselineEntity
import com.bipss.app.data.db.entity.SignalEpochEntity
import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.domain.BaselineSchedule
import com.bipss.app.domain.RiskScoreCalculator
import com.bipss.app.domain.ali.AliResult
import com.bipss.app.domain.ali.AutonomicLoad
import com.bipss.app.domain.ali.StillEpoch
import com.bipss.app.domain.baseline.EwmaState
import com.bipss.app.domain.recovery.NightInputs
import com.bipss.app.domain.recovery.RecoveryEngine
import com.bipss.app.domain.recovery.RecoveryInput
import com.bipss.app.domain.recovery.RecoveryResult
import com.bipss.app.domain.signal.SignalConfig
import com.bipss.app.domain.strain.StrainMinute
import com.bipss.app.domain.strain.StrainResult
import com.bipss.app.domain.strain.Trimp
import com.bipss.app.domain.strain.TrimpSex
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Strain for a day, or why there is none. */
sealed class StrainOutcome {
    data class Available(
        val result: StrainResult,
        /** HRrest came from the first-day fallback (lowest still 5 minutes), not from sleep. */
        val restIsEstimate: Boolean
    ) : StrainOutcome()

    data class Unavailable(
        /** The full sentence, for the Strain screen. */
        val reason: String,
        /** A short phrase for the score row, e.g. "movement data today". */
        val missing: String,
        val kind: RiskScoreCalculator.MissingKind
    ) : StrainOutcome()
}

/**
 * Everything the scores need from stored data: the baseline day count, today's
 * strain, last night's recovery and the current Autonomic Load Indicator.
 *
 * The maths lives in the pure domain objects; this class only gathers inputs
 * and keeps the nightly EWMA baselines. Each night is folded into the
 * baselines exactly once - serialised by a lock, and skipping a re-analysed
 * copy of a night already folded - and the pre-night snapshot is kept, so the
 * latest night is always judged against the nights before it.
 */
class ScoringRepository(
    private val db:    AppDatabase,
    private val prefs: UserPreferencesRepository
) {
    private val dataDayDao  = db.dataDayDao()
    private val ewmaDao     = db.ewmaBaselineDao()
    private val epochDao    = db.signalEpochDao()
    private val rawDao      = db.rawReadingDao()
    private val sleepDao    = db.sleepSessionDao()
    private val baselineDao = db.baselineStatsDao()

    /** One night folding at a time: several screens and the worker all trigger it. */
    private val nightLock = Mutex()

    // -- Everything the score calculator needs, in one place -------------------------

    /** The inputs every screen passes to [RiskScoreCalculator], so they cannot disagree. */
    data class Snapshot(
        val personal: RiskScoreCalculator.Personal,
        val recovery: RecoveryResult?,
        val strain:   RiskScoreCalculator.Pillar<RiskScoreCalculator.StrainInput>,
        val ali:      AliResult?
    )

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.Default) {
        val dayN = dayN()
        val baselines = currentBaselines()
        val strain = when (val o = strainForDay()) {
            is StrainOutcome.Available -> RiskScoreCalculator.Pillar.Value(
                RiskScoreCalculator.StrainInput(o.result.display, o.restIsEstimate, o.result.activeMinutes, o.result.scoredMinutes)
            )
            is StrainOutcome.Unavailable -> RiskScoreCalculator.Pillar.Missing(o.missing, o.kind)
        }
        Snapshot(
            personal = RiskScoreCalculator.Personal(
                dayN = dayN,
                restingHr = baselines[RecoveryInput.RESTING_HR]?.mean,
                // Ten minutes of readings before "usual" means anything.
                usualSkinTempC = baselineDao.getByType(MetricTypes.SKIN_TEMP)?.takeIf { it.count > 600 }?.mean
            ),
            recovery = latestRecovery(),
            strain = strain,
            ali = autonomicLoad()
        )
    }

    // -- Baseline day count ----------------------------------------------------------

    val dayNFlow: Flow<Int> = dataDayDao.qualifyingDaysFlow(BaselineSchedule.MIN_VALID_MINUTES)

    suspend fun dayN(): Int = dataDayDao.qualifyingDays(BaselineSchedule.MIN_VALID_MINUTES)

    // -- Strain --------------------------------------------------------------------

    suspend fun strainForDay(dayMs: Long = System.currentTimeMillis()): StrainOutcome {
        val profile = prefs.userProfile.first()
        val age = profile?.age?.takeIf { it in 10..110 }
            ?: return StrainOutcome.Unavailable(
                "Add your age in Settings - strain is scaled to your maximum heart rate.",
                "Add your age in Settings", RiskScoreCalculator.MissingKind.INPUT
            )
        val day = LocalDay.dayRangeMs(dayMs)          // half-open [first, last + 1)
        val start = day.first
        val end = day.last + 1

        val activity = rawDao.getBuckets(MetricTypes.ACTIVITY, start, end, 60_000L)
        if (activity.isEmpty()) {
            // Only blame the firmware when the band has never sent movement data.
            return if (rawDao.countFor(MetricTypes.ACTIVITY) > 0) StrainOutcome.Unavailable(
                "No movement data from the band yet today.",
                "movement data today", RiskScoreCalculator.MissingKind.BAND
            ) else StrainOutcome.Unavailable(
                "Strain needs movement data, which the band sends from firmware v2.",
                "movement data from band firmware v2", RiskScoreCalculator.MissingKind.BAND
            )
        }
        val hr = rawDao.getBuckets(MetricTypes.HR, start, end, 60_000L).associateBy { it.bucketStartMs }

        val sleepRest = currentBaselines()[RecoveryInput.RESTING_HR]?.mean
        val fallbackRest = if (sleepRest == null) lowestStillFiveMinutes(start, end) else null
        val hrRest = sleepRest ?: fallbackRest ?: return StrainOutcome.Unavailable(
            "Strain needs a few minutes of heart rate while you're still, to find your resting rate.",
            "a few still minutes of heart rate", RiskScoreCalculator.MissingKind.BAND
        )
        val hrMax = Trimp.gellishHrMax(age)
        if (hrMax <= hrRest + 20) return StrainOutcome.Unavailable(
            "Your resting and maximum heart rates are too close together to score strain.",
            "a resting heart rate well below your maximum", RiskScoreCalculator.MissingKind.BAND
        )

        val minutes = activity.map { a ->
            StrainMinute(
                minuteStartMs = a.bucketStartMs,
                hr = hr[a.bucketStartMs]?.avgValue?.toDouble(),
                activityMgS = a.avgValue?.let { it.toDouble() * a.sampleCount }
            )
        }
        val sex = when (profile?.gender) {
            com.bipss.app.data.Gender.MALE   -> TrimpSex.MALE
            com.bipss.app.data.Gender.FEMALE -> TrimpSex.FEMALE
            // Non-binary, prefer not to say, or not asked yet: the documented mean.
            else                             -> TrimpSex.UNSPECIFIED
        }
        return StrainOutcome.Available(Trimp.compute(minutes, hrRest, hrMax, sex), restIsEstimate = sleepRest == null)
    }

    /**
     * Day-1 resting-HR fallback: the lowest mean heart rate over ten
     * consecutive clean, still epochs (five minutes) today.
     */
    private suspend fun lowestStillFiveMinutes(fromMs: Long, untilMs: Long): Double? {
        val epochs = stillEpochs(fromMs, untilMs)
        return epochs.windowed(10)
            .filter { w -> w.last().startMs - w.first().startMs == 9 * 30_000L }
            .mapNotNull { w -> w.mapNotNull { it.meanHr }.takeIf { it.size == 10 }?.average() }
            .minOrNull()
    }

    // -- Recovery ------------------------------------------------------------------

    /**
     * Folds every sleep session not yet seen into the baselines, oldest first.
     * A session that overlaps the last folded night is the same night
     * re-analysed (its start may have shifted), and is skipped.
     */
    suspend fun processNewNights() = nightLock.withLock {
        val lastStart = ewmaDao.get(META_LAST_NIGHT)?.lastNightStartMs ?: Long.MIN_VALUE
        val lastEnd = ewmaDao.get(META_LAST_NIGHT_END)?.lastNightStartMs ?: Long.MIN_VALUE
        val nights = sleepDao.all().filter { it.startMs > lastStart && it.startMs >= lastEnd }
        for (session in nights) {
            val inputs = nightInputs(session)
            val current = currentBaselines()
            val updated = RecoveryEngine.updatedBaselines(inputs, current)
            val now = System.currentTimeMillis()
            db.withTransaction {
                // Snapshot first: the latest night is judged against the nights before it.
                ewmaDao.deleteWithPrefix(PREV_PREFIX)
                ewmaDao.upsertAll(current.map { (input, s) -> row(PREV_PREFIX + input.key, s, session.startMs, now) })
                ewmaDao.upsertAll(updated.map { (input, s) -> row(input.key, s, session.startMs, now) })
                ewmaDao.upsertAll(listOf(
                    EwmaBaselineEntity(META_LAST_NIGHT, 0.0, 0.0, 0, session.startMs, now),
                    // The end is kept in the same column of a second marker row.
                    EwmaBaselineEntity(META_LAST_NIGHT_END, 0.0, 0.0, 0, session.endMs, now)
                ))
            }
        }
    }

    /**
     * Recovery for the most recent night, or null when no night has ever been
     * recorded. A night that ended before yesterday is not "last night": it
     * yields no score, with the date it was recorded.
     */
    suspend fun latestRecovery(nowMs: Long = System.currentTimeMillis()): RecoveryResult? {
        processNewNights()
        val session = sleepDao.mostRecent() ?: return null
        val dayN = dayN()
        if (LocalDay.epochDay(session.endMs) != LocalDay.epochDay(nowMs)) {
            val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(session.startMs))
            return RecoveryResult(
                nightStartMs = session.startMs, score = null, stage = BaselineSchedule.stage(dayN), dayN = dayN,
                components = emptyList(), reason = "No night recorded since $date"
            )
        }
        val before = ewmaDao.all()
            .filter { it.key.startsWith(PREV_PREFIX) }
            .mapNotNull { e -> RecoveryInput.entries.firstOrNull { PREV_PREFIX + it.key == e.key }?.let { it to e.toState() } }
            .toMap()
        return RecoveryEngine.evaluate(nightInputs(session), before, dayN)
    }

    suspend fun nightInputs(session: SleepSessionEntity): NightInputs {
        val still = stillEpochs(session.startMs, session.endMs)
        val rmssd = still.filter { !it.prvLowConfidence }.mapNotNull { it.rmssdMs }.sorted()
        return NightInputs(
            nightStartMs = session.startMs,
            rmssdMs      = rmssd.takeIf { it.size >= MIN_NIGHT_EPOCHS }?.let { it[it.size / 2] },
            restingHr    = still.mapNotNull { it.meanHr }.takeIf { it.size >= MIN_NIGHT_EPOCHS }?.average(),
            sleepMinutes = session.totalSleepMs / 60_000.0,
            // One average over the whole session.
            skinTempC    = rawDao.averageBetween(MetricTypes.SKIN_TEMP, session.startMs, session.endMs)
        )
    }

    /** The baselines after every processed night; the resting-HR mean doubles as Strain's HRrest. */
    suspend fun currentBaselines(): Map<RecoveryInput, EwmaState> =
        ewmaDao.all().mapNotNull { e ->
            RecoveryInput.entries.firstOrNull { it.key == e.key }?.let { it to e.toState() }
        }.toMap()

    // -- Autonomic Load Indicator ----------------------------------------------------

    suspend fun autonomicLoad(nowMs: Long = System.currentTimeMillis()): AliResult {
        val recent = stillEpochs(nowMs - AutonomicLoad.RECENT_MS, nowMs).map(::still)
        val reference = stillEpochs(nowMs - 14 * 86_400_000L, nowMs - 2 * 3_600_000L).map(::still)
        return AutonomicLoad.evaluate(recent, reference, dayN())
    }

    // -- helpers ---------------------------------------------------------------------

    /** Clean (Good) and still epochs only, filtered in SQL rather than in memory. */
    private suspend fun stillEpochs(fromMs: Long, toMs: Long): List<SignalEpochEntity> =
        epochDao.stillGood(fromMs, toMs, SignalConfig.MOTION_FREEZE_G2)

    private fun still(e: SignalEpochEntity) = StillEpoch(e.startMs, e.meanHr, e.rmssdMs?.takeIf { !e.prvLowConfidence })

    private fun EwmaBaselineEntity.toState() = EwmaState(mean, variance, count)

    private fun row(key: String, s: EwmaState, night: Long, now: Long) =
        EwmaBaselineEntity(key, s.mean, s.variance, s.count, night, now)

    private companion object {
        const val PREV_PREFIX = "prev:"
        const val META_LAST_NIGHT = "_last_night"
        const val META_LAST_NIGHT_END = "_last_night_end"
        /** Ten clean still epochs (five minutes) before a night's PRV or resting HR counts. */
        const val MIN_NIGHT_EPOCHS = 10
    }
}
