package com.bipss.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_readings",
    indices = [Index(value = ["metricType", "timestampMs"])]
)
data class RawReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metricType:   String,   // "HR", "SPO2", "SKIN_TEMP", "PRV", "ACTIVITY", ... (see MetricTypes)
    val value:        Double,
    /**
     * True sample time, derived from the band's own uptime clock - NOT the
     * time the packet arrived. A buffered replay would otherwise stamp
     * thousands of samples at the same instant and poison every baseline.
     */
    val timestampMs:  Long,
    val isAnomalous:  Boolean = false
)

/**
 * Minute-level rollup of [RawReadingEntity].
 *
 * Raw 1 Hz data across six metrics is roughly 500 k rows per day, so a 90-day
 * Month view cannot read raw. Raw is kept for 7 days; older samples are rolled
 * into these buckets and pruned. Day view reads raw, Week/Month read this.
 */
@Entity(
    tableName = "minute_aggregates",
    primaryKeys = ["metricType", "minuteEpoch"]
)
data class MinuteAggregateEntity(
    val metricType:  String,
    /** timestampMs / 60_000. */
    val minuteEpoch: Long,
    val avg:         Float,
    val min:         Float,
    val max:         Float,
    val sampleCount: Int
)

@Entity(tableName = "baseline_stats")
data class BaselineStatsEntity(
    @PrimaryKey val metricType: String,  // "HR", "SPO2", "SKIN_TEMP"
    val mean:    Double,
    val m2:      Double,   // Welford's M2 accumulator (variance x count)
    val count:   Long,
    val stdDev:  Double    // sqrt(M2 / (count-1)), cached for fast reads
)

/** One detected night of sleep. Written once the session is judged complete. */
@Entity(tableName = "sleep_sessions", indices = [Index(value = ["startMs"], unique = true)])
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs:            Long,
    val endMs:              Long,
    /** Total sleep time - time in bed minus wake after onset. */
    val totalSleepMs:       Long,
    /** totalSleepMs / (endMs - startMs), as a percentage. */
    val efficiencyPct:      Int,
    val wakeAfterOnsetMs:   Long,
    val awakenings:         Int,
    val avgHr:              Float?  = null,
    val minHr:              Float?  = null,
    val avgSpo2:            Float?  = null
)

/**
 * One oxygen desaturation event within a sleep session.
 *
 * Stored even when rejected as a motion artefact, so the screening summary can
 * report how many candidates were discarded rather than silently dropping them.
 */
@Entity(tableName = "desaturation_events", indices = [Index(value = ["sessionId"])])
data class DesaturationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId:      Long,
    val startMs:        Long,
    val durationMs:     Long,
    val baselineSpo2:   Float,
    val nadirSpo2:      Float,
    val dropPercent:    Float,
    /** True when accelerometer activity coincided - excluded from the ODI. */
    val motionArtefact: Boolean
)

/**
 * User verdict on an indoor/outdoor inference.
 *
 * Collecting this is the feature: it is the on-device learning loop, and it
 * gives the ratio threshold something real to be tuned against later.
 */
@Entity(tableName = "context_feedback")
data class ContextFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs:      Long,
    val inferredContext:  String,
    val sensorAqi:        Int?,
    val apiAqi:           Int?,
    val userSaidCorrect:  Boolean
)

@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name:        String,
    val phoneNumber: String,
    val addedAtMs:   Long = System.currentTimeMillis()
)

@Entity(tableName = "sos_events")
data class SosEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggeredAtMs:  Long,
    val source:         String,
    val outcome:        String,
    val lat:            Double?,
    val lng:            Double?,
    val recipientCount: Int,
    val notes:          String = ""
)

/**
 * One logged drink. A table rather than DataStore counters because "today" and
 * "yesterday" are then just queries between local-midnight bounds
 * (see [com.bipss.app.domain.time.LocalDay]) - the old single-day counter
 * overwrote yesterday the moment today got its first entry.
 */
@Entity(tableName = "water_log", indices = [Index(value = ["timestampMs"])])
data class WaterLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val amountMl:    Int
)

/**
 * 30 seconds of signal-pipeline output. PRV and sleep analysis read these
 * rather than 1 Hz rows: each epoch carries its own quality verdict, so a
 * consumer can tell a clean still minute from a noisy one.
 */
@Entity(tableName = "signal_epochs")
data class SignalEpochEntity(
    @PrimaryKey val startMs: Long,
    val durationMs:              Int,
    val coveredSeconds:          Int,
    val meanHr:                  Double?,
    val minHr:                   Double?,
    val maxHr:                   Double?,
    val beatCount:               Int,
    /** SignalQuality name: GOOD, DEGRADED or POOR. */
    val quality:                 String,
    val rmssdMs:                 Double?,
    val sdnnMs:                  Double?,
    val prvInterpolatedFraction: Double,
    val prvLowConfidence:        Boolean,
    val motionEnergy:            Double,
    val coastFraction:           Double
) {
    companion object {
        fun from(e: com.bipss.app.domain.signal.SignalEpoch) = SignalEpochEntity(
            startMs = e.startMs, durationMs = e.durationMs, coveredSeconds = e.coveredSeconds,
            meanHr = e.meanHr, minHr = e.minHr, maxHr = e.maxHr, beatCount = e.beatCount,
            quality = e.quality.name, rmssdMs = e.rmssdMs, sdnnMs = e.sdnnMs,
            prvInterpolatedFraction = e.prvInterpolatedFraction, prvLowConfidence = e.prvLowConfidence,
            motionEnergy = e.motionEnergy, coastFraction = e.coastFraction
        )
    }
}

/**
 * Minutes of measured heart rate per local calendar day. A day with at least
 * BaselineSchedule.MIN_VALID_MINUTES counts toward the personal baseline.
 */
@Entity(tableName = "data_days")
data class DataDayEntity(
    @PrimaryKey val epochDay: Long,
    val validMinutes: Int
)

/**
 * One exponentially weighted baseline (mean, variance, count) per nightly
 * recovery input, plus a "prev:" snapshot taken before the most recent night
 * and a "_last_night" marker so each night is folded in exactly once.
 */
@Entity(tableName = "ewma_baselines")
data class EwmaBaselineEntity(
    @PrimaryKey val key: String,
    val mean:             Double,
    val variance:         Double,
    val count:            Int,
    val lastNightStartMs: Long,
    val updatedAtMs:      Long
)

/**
 * One environment sync, kept as a short ring buffer (the last
 * EnvSnapshotDao.KEEP rows) of what the 15-minute job actually fetched: the
 * scalar features as columns for querying, the full cached snapshot as JSON.
 * Written only by that job and at least 10 minutes apart, so the rows are an
 * evenly spaced series rather than a record of how often the app was opened.
 * Location-derived, so it is cleared when location permission is.
 */
@Entity(tableName = "env_snapshots", indices = [Index(value = ["takenAtMs"], unique = true)])
data class EnvSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val takenAtMs:        Long,
    val aqi:              Int?,
    val tempC:            Float?,
    val humidityPct:      Float?,
    val uvIndex:          Float?,
    val pressureHpa:      Float?,
    val windKph:          Float?,
    /** AlertSeverity.rank of the most severe active alert; 0 when none. */
    val maxAlertSeverity: Int,
    val json:             String
)

/**
 * One indoor/outdoor check (spec 5.9): the context judged from GNSS signal
 * and movement, the evidence, and both air readings at that moment. No
 * position is stored - the reconciliation needs where the user was relative
 * to a building, not where on the map.
 */
@Entity(tableName = "aqi_context_samples", indices = [Index(value = ["takenAtMs"])])
data class AqiContextSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val takenAtMs:          Long,
    /** EnvContextKind name. */
    val context:            String,
    val confidence:         Float,
    /** The classifier's evidence, one line each. */
    val evidence:           String,
    val bandUba:            Int?,
    val cpcbAqi:            Int?,
    val satellitesVisible:  Int,
    val satellitesUsed:     Int,
    val topCn0DbHz:         Double?,
    val accuracyM:          Double?,
    val speedMps:           Double?,
    val displacementM:      Double?,
    val activityMgSPerMin:  Double?
)

/**
 * A logged period, as local calendar days (LocalDay epoch days). Kept on
 * this phone only - never exported to the shared-data service; included in
 * the user's own Drive backup when that is on.
 */
@Entity(tableName = "cycle_periods", indices = [Index(value = ["startEpochDay"], unique = true)])
data class CyclePeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochDay: Long,
    /** Inclusive; null while the period is ongoing. */
    val endEpochDay:   Long?
)

/** A free-text note for one day of the cycle (mood, symptoms, energy - unstructured). */
@Entity(tableName = "cycle_day_notes")
data class CycleDayNoteEntity(
    @PrimaryKey val epochDay: Long,
    val note:        String,
    val updatedAtMs: Long
)

/**
 * One local calendar day, boiled down. BioAge looks back six months and raw
 * readings are only kept for a week (minute averages for ninety days), so the
 * figures it needs are written here once a day and kept. validMinutes is the
 * number of minutes with a measured heart rate: a day with few of them is a
 * day the band was mostly off, and its steps and zone minutes are not trusted.
 */
@Entity(tableName = "daily_summary")
data class DailySummaryEntity(
    @PrimaryKey val epochDay: Long,
    val validMinutes:  Int,
    /** The night that ended on this day. */
    val sleepMinutes:  Int?,
    /** Minute of the day the night began and ended (0..1439). */
    val sleepStartMin: Int?,
    val sleepEndMin:   Int?,
    /** Mean heart rate over that night's clean, still epochs. */
    val restingHr:     Double?,
    val steps:         Int?,
    /** Minutes with movement at 40-60 / 60-70 / 70-80 / 80+ percent of heart-rate reserve. */
    val zoneMin1:      Int,
    val zoneMin2:      Int,
    val zoneMin3:      Int,
    val zoneMin45:     Int,
    /** Logged or confirmed strength-type sessions (weights, yoga, functional). */
    val strengthMin:   Int,
    val workoutCount:  Int,
    val recovery:      Int?,
    val sleepScore:    Int?,
    val strain:        Float?,
    val waterMl:       Int,
    val updatedAtMs:   Long
)

/** A sustained effort found in the heart-rate and movement data, for the user to name or dismiss. */
@Entity(tableName = "detected_workouts", indices = [Index(value = ["startMs"], unique = true)])
data class DetectedWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs:  Long,
    val endMs:    Long,
    val minutes:  Int,
    val meanHr:   Double,
    val maxHr:    Double,
    /** Mean heart-rate reserve over the effort, 0..1. */
    val meanHrr:  Double,
    val trimp:    Double,
    /** Steps per minute over the effort, when the band counted steps. */
    val cadence:  Double?,
    /** WALK, RUN or WORKOUT: what the data looks like. */
    val guessedType: String,
    /** WALK, RUN, CYCLE, WORKOUT (strength) or YOGA: what it counts as. */
    val type:     String,
    /** NEW until the user reviews it, then CONFIRMED or DISMISSED. */
    val status:   String
)

/** One yes/no answer in the daily journal. */
@Entity(tableName = "journal_log", primaryKeys = ["epochDay", "habit"])
data class JournalEntryEntity(
    val epochDay: Long,
    val habit:    String,
    val yes:      Boolean
)

/** The day's VO2 max estimate and the two estimates behind it. */
@Entity(tableName = "vo2_estimates")
data class Vo2EstimateEntity(
    @PrimaryKey val epochDay: Long,
    val passive:        Double?,
    val active:         Double?,
    val value:          Double,
    /** Typical error (one standard deviation) of value, mL/kg/min. */
    val sigma:          Double,
    val activeSegments: Int
)

/** BioAge as it stood on one day, for the trend line and the weekly report. */
@Entity(tableName = "bioage_history")
data class BioAgeEntity(
    @PrimaryKey val epochDay: Long,
    val chronologicalAge: Int,
    val bioAge:           Double,
    val pace:             Double?
)
