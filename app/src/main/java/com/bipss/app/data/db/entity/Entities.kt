package com.bipss.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "raw_readings")
data class RawReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metricType:   String,   // "HR", "SPO2", "SKIN_TEMP", "ACCEL_X", "ACCEL_Y", "ACCEL_Z"
    val value:        Double,
    val timestampMs:  Long,
    val isAnomalous:  Boolean = false
)

@Entity(tableName = "baseline_stats")
data class BaselineStatsEntity(
    @PrimaryKey val metricType: String,  // "HR", "SPO2", "SKIN_TEMP"
    val mean:    Double,
    val m2:      Double,   // Welford's M2 accumulator (variance × count)
    val count:   Long,
    val stdDev:  Double    // sqrt(M2 / (count-1)), cached for fast reads
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
