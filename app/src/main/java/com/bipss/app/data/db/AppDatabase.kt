package com.bipss.app.data.db

import androidx.room.*
import com.bipss.app.data.db.dao.*
import com.bipss.app.data.db.entity.*
import com.bipss.app.data.db.migration.*

@Database(
    entities = [
        RawReadingEntity::class,
        BaselineStatsEntity::class,
        EmergencyContactEntity::class,
        SosEventEntity::class
    ],
    version  = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawReadingDao():         RawReadingDao
    abstract fun baselineStatsDao():      BaselineStatsDao
    abstract fun emergencyContactDao():   EmergencyContactDao
    abstract fun sosEventDao():           SosEventDao
}
