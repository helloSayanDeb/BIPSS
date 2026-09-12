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
}

@Dao
interface DesaturationEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<DesaturationEventEntity>)

    @Query("SELECT * FROM desaturation_events WHERE sessionId = :sessionId ORDER BY startMs ASC")
    suspend fun forSession(sessionId: Long): List<DesaturationEventEntity>

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
