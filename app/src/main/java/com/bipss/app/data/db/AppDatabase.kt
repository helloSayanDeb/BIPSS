package com.bipss.app.data.db

import androidx.room.*
import com.bipss.app.data.db.dao.*
import com.bipss.app.data.db.entity.*

@Database(
    entities = [
        RawReadingEntity::class,
        MinuteAggregateEntity::class,
        BaselineStatsEntity::class,
        SleepSessionEntity::class,
        DesaturationEventEntity::class,
        ContextFeedbackEntity::class,
        EmergencyContactEntity::class,
        SosEventEntity::class
    ],
    version  = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawReadingDao():         RawReadingDao
    abstract fun minuteAggregateDao():    MinuteAggregateDao
    abstract fun baselineStatsDao():      BaselineStatsDao
    abstract fun sleepSessionDao():       SleepSessionDao
    abstract fun desaturationEventDao():  DesaturationEventDao
    abstract fun contextFeedbackDao():    ContextFeedbackDao
    abstract fun emergencyContactDao():   EmergencyContactDao
    abstract fun sosEventDao():           SosEventDao
}
