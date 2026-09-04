package com.bipss.app.data.db.dao

import androidx.room.*
import com.bipss.app.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RawReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: RawReadingEntity)

    @Query("SELECT * FROM raw_readings WHERE metricType = :type ORDER BY timestampMs DESC LIMIT :limit")
    fun recentReadings(type: String, limit: Int = 50): Flow<List<RawReadingEntity>>

    @Query("DELETE FROM raw_readings WHERE timestampMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)
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
