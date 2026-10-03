package com.bipss.app.debug

import com.bipss.app.data.db.dao.BioAgeDao
import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.dao.DetectedWorkoutDao
import com.bipss.app.data.db.dao.JournalDao
import com.bipss.app.data.db.entity.BioAgeEntity
import com.bipss.app.data.db.entity.DailySummaryEntity
import com.bipss.app.data.db.entity.DetectedWorkoutEntity
import com.bipss.app.data.db.entity.JournalEntryEntity
import com.bipss.app.domain.journal.Habit
import com.bipss.app.domain.time.LocalDay
import kotlin.random.Random

/**
 * Debug builds only, and only into the simulation database: a believable
 * stretch of past days (sleep, steps, heart-rate zones, recovery, journal
 * answers) so BioAge, the weekly report, the sleep planner and the journal
 * insights can be looked at without waiting months. The values are made up
 * and never reach real data.
 */
class SimHistorySeeder(
    private val summaries: DailySummaryDao,
    private val journal: JournalDao,
    private val workouts: DetectedWorkoutDao,
    private val bioAge: BioAgeDao
) {
    suspend fun seed(days: Int, nowMs: Long = System.currentTimeMillis(), age: Int = 30) {
        val today = LocalDay.epochDay(nowMs)
        val rnd = Random(42)
        val drank = HashSet<Long>()
        for (i in days downTo 1) {
            val day = today - i
            // The evening's drink lowers the next morning's recovery: that is what the journal should find.
            val alcoholEve = rnd.nextInt(4) == 0
            if (alcoholEve) drank += day
            val after = (day - 1) in drank
            val bed = (23 * 60 + rnd.nextInt(-40, 41) + 1440) % 1440
            val sleep = (7 * 60 + rnd.nextInt(-50, 61))
            val progress = 1f - i.toFloat() / days
            summaries.upsert(DailySummaryEntity(
                epochDay = day, validMinutes = rnd.nextInt(900, 1300),
                sleepMinutes = sleep, sleepStartMin = bed, sleepEndMin = (bed + sleep + 15) % 1440,
                restingHr = 63.0 - 5.0 * progress + rnd.nextDouble(-1.5, 1.5),
                steps = rnd.nextInt(5000, 11500),
                zoneMin1 = rnd.nextInt(12, 32), zoneMin2 = rnd.nextInt(3, 11), zoneMin3 = rnd.nextInt(2, 7), zoneMin45 = rnd.nextInt(0, 5),
                strengthMin = if (i % 3 == 0) rnd.nextInt(30, 46) else 0,
                workoutCount = if (i % 3 == 0) 1 else 0,
                recovery = (74 - (if (after) 16 else 0) + rnd.nextInt(-8, 9)).coerceIn(5, 99),
                sleepScore = rnd.nextInt(60, 92), strain = (rnd.nextDouble(6.0, 15.0)).toFloat(),
                waterMl = rnd.nextInt(1200, 2800), updatedAtMs = nowMs
            ))
            Habit.entries.forEach { h ->
                val yes = if (h == Habit.ALCOHOL) alcoholEve else rnd.nextBoolean()
                journal.upsert(JournalEntryEntity(day, h.key, yes))
            }
            if (i % 7 == 0) bioAge.upsert(BioAgeEntity(day, age, age - 1.0 - 1.5 * progress + rnd.nextDouble(-0.3, 0.3), null))
        }
        // One effort for the user to name.
        val start = nowMs - 20 * 3_600_000L
        workouts.insert(DetectedWorkoutEntity(
            startMs = start, endMs = start + 34 * 60_000L, minutes = 34, meanHr = 134.0, maxHr = 158.0,
            meanHrr = 0.62, trimp = 38.0, cadence = 112.0, guessedType = "WALK", type = "Walk", status = "NEW"
        ))
    }
}
