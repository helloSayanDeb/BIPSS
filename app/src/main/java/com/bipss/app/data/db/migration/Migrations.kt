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
