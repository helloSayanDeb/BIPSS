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
        SosEventEntity::class,
        WaterLogEntity::class,
        SignalEpochEntity::class
    ],
    version  = 8,
    // Exported from v6 so every later hand-written migration can be checked
    // against the schema Room actually expects (see app/schemas/).
    exportSchema = true
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
    abstract fun waterLogDao():           WaterLogDao
    abstract fun signalEpochDao():        SignalEpochDao
}
