package com.bipss.app.data

import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.dao.DetectedWorkoutDao
import com.bipss.app.data.db.dao.SleepSessionDao
import com.bipss.app.data.db.dao.WaterLogDao
import com.bipss.app.data.db.entity.DailySummaryEntity
import com.bipss.app.domain.strain.Trimp
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/** Activity types that count as strength time for BioAge. */
val STRENGTH_ACTIVITY_TYPES = setOf("Strength", "Workout", "Yoga")

/**
 * Writes one daily_summary row per local day. BioAge looks back six months,
 * and raw readings last a week, so the figures it needs are taken here while
 * they are still available and kept.
 *
 * Recent days are recomputed each time (a day is not done until midnight);
 * older days are filled in once from the minute averages and then left alone.
 */
class DailySummaryRepository(
    private val minutes:   MinuteData,
    private val summaries: DailySummaryDao,
    private val sleepDao:  SleepSessionDao,
    private val waterDao:  WaterLogDao,
    private val workouts:  DetectedWorkoutDao,
    private val scoring:   ScoringRepository,
    private val prefs:     UserPreferencesRepository,
    private val daily:     DailyActivityRepository
) {
    /** Recomputes today and the days just before it. */
    suspend fun refresh(recentDays: Int = 3, nowMs: Long = System.currentTimeMillis()) {
        val today = LocalDay.epochDay(nowMs)
        for (d in (today - recentDays + 1)..today) summarize(d)?.let { summaries.upsert(it) }
    }

    /** One-off fill of the days further back that have no row yet (minute averages reach back ninety days). */
    suspend fun backfill(nowMs: Long = System.currentTimeMillis()) {
        val today = LocalDay.epochDay(nowMs)
        for (d in (today - 89)..(today - 3)) {
            if (summaries.get(d) == null) summarize(d)?.let { summaries.upsert(it) }
        }
    }

    suspend fun summarize(day: Long, zone: ZoneId = ZoneId.systemDefault()): DailySummaryEntity? {
        val start = LocalDay.startOfDayMs(day, zone)
        val end = LocalDay.startOfDayMs(day + 1, zone)
        val series = minutes.workMinutes(start, end)
        val valid = series.count { it.hr != null }

        // Zone minutes: movement together with a heart rate in the zone.
        var z1 = 0; var z2 = 0; var z3 = 0; var z45 = 0
        val age = prefs.userProfile.first()?.age?.takeIf { it in 10..110 }
        val rest = if (age == null || valid == 0) null else scoring.restingHrOrFallback(start, end)
        if (age != null && rest != null) {
            val max = Trimp.gellishHrMax(age)
            if (max > rest + 20) for (m in series) {
                val hr = m.hr ?: continue
                if ((m.activityMgS ?: 0.0) < Trimp.ACTIVE_MG_S_PER_MIN) continue
                val hrr = (hr - rest) / (max - rest)
                when {
                    hrr >= 0.8 -> z45++
                    hrr >= 0.7 -> z3++
                    hrr >= 0.6 -> z2++
                    hrr >= 0.4 -> z1++
                }
            }
        }

        val session = sleepDao.since(start - 86_400_000L)
            .filter { it.endMs in start until end }.maxByOrNull { it.totalSleepMs }
        fun minuteOfDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }
        val nightRhr = session?.let { runCatching { scoring.nightInputs(it).restingHr }.getOrNull() }

        val data = daily.data.first()
        val dayHealth = data.health[day]
        val manual = data.activities.filter { LocalDay.epochDay(it.atMs, zone) == day }
        val confirmed = workouts.between(start, end).filter { it.status == "CONFIRMED" }
        val strength = manual.filter { it.name in STRENGTH_ACTIVITY_TYPES }.sumOf { it.minutes } +
            confirmed.filter { it.type in STRENGTH_ACTIVITY_TYPES }.sumOf { it.minutes }
        val water = waterDao.totalsBetween(start, end).totalMl
        val steps = data.bandSteps[day]

        val empty = valid == 0 && session == null && steps == null && water == 0 && strength == 0
        if (empty) return null
        return DailySummaryEntity(
            epochDay = day,
            validMinutes = valid,
            sleepMinutes = session?.let { (it.totalSleepMs / 60_000L).toInt() },
            sleepStartMin = session?.let { minuteOfDay(it.startMs) },
            sleepEndMin = session?.let { minuteOfDay(it.endMs) },
            restingHr = nightRhr,
            steps = steps,
            zoneMin1 = z1, zoneMin2 = z2, zoneMin3 = z3, zoneMin45 = z45,
            strengthMin = strength,
            workoutCount = manual.size + confirmed.size,
            recovery = dayHealth?.recovery,
            sleepScore = dayHealth?.sleep,
            strain = dayHealth?.strain,
            waterMl = water,
            updatedAtMs = System.currentTimeMillis()
        )
    }
}
