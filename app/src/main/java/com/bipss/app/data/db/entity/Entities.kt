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
    val metricType:   String,   // "HR", "SPO2", "SKIN_TEMP", "ACCEL_MAG", "HRV", ...
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
