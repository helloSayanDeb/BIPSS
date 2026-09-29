package com.bipss.app.data.db.dao

import androidx.room.*
import com.bipss.app.data.db.entity.*
import kotlinx.coroutines.flow.Flow

/** Min/avg/max/count over a metric in a window. Null fields when no rows matched. */
data class MetricStats(
    @ColumnInfo(name = "minValue") val minValue: Float?,
    @ColumnInfo(name = "maxValue") val maxValue: Float?,
    @ColumnInfo(name = "avgValue") val avgValue: Float?,
    @ColumnInfo(name = "sampleCount") val sampleCount: Int
)

/**
 * One time bucket of a metric: sample-weighted mean plus the true extremes and
 * how many samples went into it. [bucketStartMs] is aligned to the bucket size.
 */
data class MetricBucket(
    @ColumnInfo(name = "bucketStartMs") val bucketStartMs: Long,
    @ColumnInfo(name = "avgValue")      val avgValue:      Double,
    @ColumnInfo(name = "minValue")      val minValue:      Double,
    @ColumnInfo(name = "maxValue")      val maxValue:      Double,
    @ColumnInfo(name = "sampleCount")   val sampleCount:   Int
)

@Dao
interface RawReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: RawReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<RawReadingEntity>)

    @Query("SELECT * FROM raw_readings WHERE metricType = :type ORDER BY timestampMs DESC LIMIT :limit")
    fun recentReadings(type: String, limit: Int = 50): Flow<List<RawReadingEntity>>

    @Query("""
        SELECT * FROM raw_readings
        WHERE metricType = :type AND timestampMs BETWEEN :startMs AND :endMs
        ORDER BY timestampMs ASC
    """)
    suspend fun getRange(type: String, startMs: Long, endMs: Long): List<RawReadingEntity>

    /** Excludes anomalous samples so a single spike cannot move the summary. */
    @Query("""
        SELECT MIN(value) AS minValue, MAX(value) AS maxValue,
               AVG(value) AS avgValue, COUNT(*) AS sampleCount
        FROM raw_readings
        WHERE metricType = :type AND timestampMs BETWEEN :startMs AND :endMs
          AND isAnomalous = 0
    """)
    suspend fun getStats(type: String, startMs: Long, endMs: Long): MetricStats?

    /** Lowest sustained values, used as a resting-HR proxy. */
    @Query("""
        SELECT AVG(value) FROM (
            SELECT value FROM raw_readings
            WHERE metricType = :type AND timestampMs BETWEEN :startMs AND :endMs
              AND isAnomalous = 0
            ORDER BY value ASC LIMIT :sampleCount
        )
    """)
    suspend fun getRestingAverage(type: String, startMs: Long, endMs: Long, sampleCount: Int = 60): Double?

    /**
     * Raw samples grouped into [bucketMs]-wide buckets, anomalies excluded.
     * Charts read this rather than every 1 Hz row (a day is ~86 k rows per metric).
     */
    @Query("""
        SELECT (timestampMs / :bucketMs) * :bucketMs AS bucketStartMs,
               AVG(value) AS avgValue, MIN(value) AS minValue, MAX(value) AS maxValue,
               COUNT(*) AS sampleCount
        FROM raw_readings
        WHERE metricType = :type AND timestampMs BETWEEN :startMs AND :endMs AND isAnomalous = 0
        GROUP BY timestampMs / :bucketMs
        ORDER BY bucketStartMs ASC
    """)
    suspend fun getBuckets(type: String, startMs: Long, endMs: Long, bucketMs: Long): List<MetricBucket>

    @Query("SELECT MIN(timestampMs) FROM raw_readings")
    suspend fun earliestTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM raw_readings WHERE metricType = :type")
    suspend fun countFor(type: String): Int

    @Query("SELECT * FROM raw_readings WHERE timestampMs < :olderThanMs ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun getOlderThan(olderThanMs: Long, limit: Int = 20_000): List<RawReadingEntity>

    @Query("DELETE FROM raw_readings WHERE timestampMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)
}

@Dao
interface MinuteAggregateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MinuteAggregateEntity>)

    @Query("""
        SELECT * FROM minute_aggregates
        WHERE metricType = :type AND minuteEpoch BETWEEN :startMinute AND :endMinute
        ORDER BY minuteEpoch ASC
    """)
    suspend fun getRange(type: String, startMinute: Long, endMinute: Long): List<MinuteAggregateEntity>

    @Query("""
        SELECT MIN(min) AS minValue, MAX(max) AS maxValue,
               AVG(avg) AS avgValue, SUM(sampleCount) AS sampleCount
        FROM minute_aggregates
        WHERE metricType = :type AND minuteEpoch BETWEEN :startMinute AND :endMinute
    """)
    suspend fun getStats(type: String, startMinute: Long, endMinute: Long): MetricStats?

    /**
     * Minute aggregates regrouped into [bucketMs]-wide buckets. The mean is
     * weighted by each minute's sample count - an unweighted AVG(avg) would let
     * a minute with one sample count as much as a minute with sixty.
     */
    @Query("""
        SELECT ((minuteEpoch * 60000) / :bucketMs) * :bucketMs AS bucketStartMs,
               SUM(avg * sampleCount) / SUM(sampleCount) AS avgValue,
               MIN(min) AS minValue, MAX(max) AS maxValue,
               SUM(sampleCount) AS sampleCount
        FROM minute_aggregates
        WHERE metricType = :type AND minuteEpoch BETWEEN :startMinute AND :endMinute AND sampleCount > 0
        GROUP BY (minuteEpoch * 60000) / :bucketMs
        ORDER BY bucketStartMs ASC
    """)
    suspend fun getBuckets(type: String, startMinute: Long, endMinute: Long, bucketMs: Long): List<MetricBucket>

    @Query("SELECT MIN(minuteEpoch) FROM minute_aggregates")
    suspend fun earliestMinute(): Long?

    @Query("DELETE FROM minute_aggregates WHERE minuteEpoch < :olderThanMinute")
    suspend fun pruneOlderThan(olderThanMinute: Long)
}

@Dao
interface BaselineStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: BaselineStatsEntity)

    @Query("SELECT * FROM baseline_stats")
    fun all(): Flow<List<BaselineStatsEntity>>

    @Query("SELECT * FROM baseline_stats WHERE metricType = :type")
    suspend fun getByType(type: String): BaselineStatsEntity?
}

@Dao
interface SleepSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SleepSessionEntity): Long

    @Query("SELECT * FROM sleep_sessions ORDER BY startMs ASC")
    suspend fun all(): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_sessions ORDER BY startMs ASC")
    fun allFlow(): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions ORDER BY startMs DESC LIMIT 1")
    suspend fun mostRecent(): SleepSessionEntity?

    @Query("SELECT * FROM sleep_sessions WHERE startMs >= :sinceMs ORDER BY startMs ASC")
    suspend fun since(sinceMs: Long): List<SleepSessionEntity>

    @Query("SELECT COUNT(*) FROM sleep_sessions")
    suspend fun count(): Int

    @Query("SELECT id FROM sleep_sessions WHERE startMs < :endMs AND endMs > :startMs")
    suspend fun overlappingIds(startMs: Long, endMs: Long): List<Long>

    @Query("DELETE FROM sleep_sessions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface DesaturationEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<DesaturationEventEntity>)

    @Query("SELECT * FROM desaturation_events WHERE sessionId = :sessionId ORDER BY startMs ASC")
    suspend fun forSession(sessionId: Long): List<DesaturationEventEntity>

    @Query("DELETE FROM desaturation_events WHERE sessionId IN (:sessionIds)")
    suspend fun deleteForSessions(sessionIds: List<Long>)

    /** Only non-artefact events count toward the ODI. */
    @Query("SELECT COUNT(*) FROM desaturation_events WHERE sessionId = :sessionId AND motionArtefact = 0")
    suspend fun validCountForSession(sessionId: Long): Int
}

@Dao
interface ContextFeedbackDao {
    @Insert
    suspend fun insert(feedback: ContextFeedbackEntity)

    @Query("SELECT * FROM context_feedback ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<ContextFeedbackEntity>

    @Query("SELECT COUNT(*) FROM context_feedback")
    suspend fun count(): Int
}

@Dao
interface EmergencyContactDao {
    @Query("SELECT * FROM emergency_contacts ORDER BY addedAtMs ASC")
    fun all(): Flow<List<EmergencyContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: EmergencyContactEntity)

    @Delete
    suspend fun delete(contact: EmergencyContactEntity)
}

@Dao
interface SosEventDao {
    @Insert
    suspend fun insert(event: SosEventEntity)

    @Query("SELECT * FROM sos_events ORDER BY triggeredAtMs DESC LIMIT 50")
    fun recentEvents(): Flow<List<SosEventEntity>>
}

/** Count and total volume of drinks in a window. */
data class WaterTotals(
    @ColumnInfo(name = "drinks") val drinks: Int,
    @ColumnInfo(name = "totalMl") val totalMl: Int
)

@Dao
interface WaterLogDao {
    @Insert
    suspend fun insert(entry: WaterLogEntity)

    /** [startMs] inclusive, [endMs] exclusive - pass LocalDay ranges. */
    @Query("""
        SELECT COUNT(*) AS drinks, COALESCE(SUM(amountMl), 0) AS totalMl
        FROM water_log WHERE timestampMs >= :startMs AND timestampMs < :endMs
    """)
    suspend fun totalsBetween(startMs: Long, endMs: Long): WaterTotals

    @Query("""
        SELECT COUNT(*) AS drinks, COALESCE(SUM(amountMl), 0) AS totalMl
        FROM water_log WHERE timestampMs >= :startMs AND timestampMs < :endMs
    """)
    fun totalsBetweenFlow(startMs: Long, endMs: Long): Flow<WaterTotals>

    @Query("SELECT MAX(timestampMs) FROM water_log")
    fun lastLogMsFlow(): Flow<Long?>

    @Query("SELECT MAX(timestampMs) FROM water_log")
    suspend fun lastLogMs(): Long?

    @Query("SELECT * FROM water_log ORDER BY timestampMs ASC")
    suspend fun all(): List<WaterLogEntity>

    @Query("DELETE FROM water_log WHERE timestampMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)
}

@Dao
interface SignalEpochDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(epoch: SignalEpochEntity)

    @Query("SELECT * FROM signal_epochs WHERE startMs >= :fromMs AND startMs < :toMs ORDER BY startMs")
    suspend fun range(fromMs: Long, toMs: Long): List<SignalEpochEntity>

    /** Clean, still epochs with a PRV value - the ones a resting PRV figure may come from. */
    @Query(
        "SELECT * FROM signal_epochs WHERE startMs >= :fromMs AND quality = 'GOOD' " +
            "AND rmssdMs IS NOT NULL AND prvLowConfidence = 0 AND motionEnergy < :maxMotion " +
            "ORDER BY startMs DESC LIMIT :limit"
    )
    suspend fun restingPrvEpochs(fromMs: Long, maxMotion: Double, limit: Int): List<SignalEpochEntity>

    @Query("DELETE FROM signal_epochs WHERE startMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)
}
