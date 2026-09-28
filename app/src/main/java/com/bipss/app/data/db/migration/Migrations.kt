package com.bipss.app.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2: adds raw_readings and baseline_stats tables */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `raw_readings` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `metricType` TEXT NOT NULL,
                `value` REAL NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                `isAnomalous` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `baseline_stats` (
                `metricType` TEXT PRIMARY KEY NOT NULL,
                `mean` REAL NOT NULL,
                `m2` REAL NOT NULL,
                `count` INTEGER NOT NULL,
                `stdDev` REAL NOT NULL
            )
        """.trimIndent())
    }
}

/** v2 → v3: adds emergency_contacts table */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `emergency_contacts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `phoneNumber` TEXT NOT NULL,
                `addedAtMs` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

/** v3 → v4: adds sos_events audit table */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `sos_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `triggeredAtMs` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `outcome` TEXT NOT NULL,
                `lat` REAL,
                `lng` REAL,
                `recipientCount` INTEGER NOT NULL,
                `notes` TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
    }
}

/**
 * v4 -> v5: history, sleep and the learning loop.
 *
 * Adds minute_aggregates (so Week/Month views can read 90 days without keeping
 * 90 days of 1 Hz raw rows), sleep_sessions and desaturation_events for the
 * overnight pipeline, context_feedback for the indoor/outdoor learning loop,
 * and an index on raw_readings(metricType, timestampMs) because every metric
 * detail query filters on exactly that pair.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `minute_aggregates` (
                `metricType` TEXT NOT NULL,
                `minuteEpoch` INTEGER NOT NULL,
                `avg` REAL NOT NULL,
                `min` REAL NOT NULL,
                `max` REAL NOT NULL,
                `sampleCount` INTEGER NOT NULL,
                PRIMARY KEY(`metricType`, `minuteEpoch`)
            )
        """.trimIndent())

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `sleep_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `startMs` INTEGER NOT NULL,
                `endMs` INTEGER NOT NULL,
                `totalSleepMs` INTEGER NOT NULL,
                `efficiencyPct` INTEGER NOT NULL,
                `wakeAfterOnsetMs` INTEGER NOT NULL,
                `awakenings` INTEGER NOT NULL,
                `avgHr` REAL,
                `minHr` REAL,
                `avgSpo2` REAL
            )
        """.trimIndent())
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_sessions_startMs` ON `sleep_sessions` (`startMs`)"
        )

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `desaturation_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `startMs` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `baselineSpo2` REAL NOT NULL,
                `nadirSpo2` REAL NOT NULL,
                `dropPercent` REAL NOT NULL,
                `motionArtefact` INTEGER NOT NULL
            )
        """.trimIndent())
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_desaturation_events_sessionId` ON `desaturation_events` (`sessionId`)"
        )

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `context_feedback` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                `inferredContext` TEXT NOT NULL,
                `sensorAqi` INTEGER,
                `apiAqi` INTEGER,
                `userSaidCorrect` INTEGER NOT NULL
            )
        """.trimIndent())

        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_raw_readings_metricType_timestampMs` " +
            "ON `raw_readings` (`metricType`, `timestampMs`)"
        )
    }
}

/**
 * v5 → v6: water_log. Drinks were previously a single-day counter in DataStore
 * keyed on the UTC day; the counter itself is migrated into this table once, at
 * start-up, by [com.bipss.app.data.WaterRepository.migrateLegacyCounter].
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `water_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                `amountMl` INTEGER NOT NULL
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_water_log_timestampMs` ON `water_log` (`timestampMs`)")
    }
}

/**
 * v6 -> v7: the legacy variability metric key becomes "PRV". The band times
 * pulse waves at the wrist, so the value is pulse rate variability.
 * No schema change - only the stored key. (baseline_stats is keyed by metric
 * type, and no "PRV" row can exist before this migration.)
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("UPDATE `raw_readings` SET `metricType` = 'PRV' WHERE `metricType` = 'HRV'")
        database.execSQL("UPDATE `minute_aggregates` SET `metricType` = 'PRV' WHERE `metricType` = 'HRV'")
        database.execSQL("UPDATE `baseline_stats` SET `metricType` = 'PRV' WHERE `metricType` = 'HRV'")
    }
}
