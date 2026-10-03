package com.bipss.app.data

import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.dao.Vo2Dao
import com.bipss.app.data.db.entity.Vo2EstimateEntity
import com.bipss.app.domain.bioage.BioSex
import com.bipss.app.domain.recovery.RecoveryInput
import com.bipss.app.domain.strain.Trimp
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.domain.vo2.Vo2Max
import com.bipss.app.domain.workout.WorkoutDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

fun Gender?.toBioSex(): BioSex = when (this) {
    Gender.MALE -> BioSex.MALE
    Gender.FEMALE -> BioSex.FEMALE
    else -> BioSex.OTHER
}

/**
 * The daily VO2 max estimate: a passive one from measured activity and
 * resting heart rate, and an active one from steady walks and runs, combined.
 * Each day's own active estimates are kept in its row, so a walk stays
 * counted for thirty days after the minute data behind it is gone.
 */
class Vo2Repository(
    private val minutes:   MinuteData,
    private val summaries: DailySummaryDao,
    private val dao:       Vo2Dao,
    private val scoring:   ScoringRepository,
    private val prefs:     UserPreferencesRepository
) {
    val latest: Flow<Vo2EstimateEntity?> = dao.latestFlow()

    fun since(day: Long): Flow<List<Vo2EstimateEntity>> = dao.sinceFlow(day)

    suspend fun refresh(nowMs: Long = System.currentTimeMillis()) {
        val profile = prefs.userProfile.first() ?: return
        val age = profile.age.takeIf { it in 10..110 } ?: return
        val today = LocalDay.epochDay(nowMs)
        val hrMax = Trimp.gellishHrMax(age)
        val sex = profile.gender.toBioSex()

        val recent = summaries.between(today - 27, today)
        val worn = recent.filter { it.validMinutes >= 600 }
        val rest = scoring.currentBaselines()[RecoveryInput.RESTING_HR]?.mean
            ?: recent.mapNotNull { it.restingHr }.takeIf { it.isNotEmpty() }?.average()

        val passive = if (worn.size < 7) null else {
            val n = worn.size
            val moderate = worn.sumOf { it.zoneMin1 }.toDouble() / n * 7
            val vigorous = worn.sumOf { it.zoneMin2 + it.zoneMin3 + it.zoneMin45 }.toDouble() / n * 7
            val steps = worn.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()
            Vo2Max.passive(age, sex, profile.heightCm.toDouble(), profile.weightKg.toDouble(),
                Vo2Max.activityRating(moderate, vigorous, steps), hrMax, rest)
        }

        // Steady walks and runs in the days whose minute data is still at hand.
        for (d in (today - 2)..today) {
            val start = LocalDay.startOfDayMs(d)
            val segments = WorkoutDetector.steadySegments(
                minutes.workMinutes(start, LocalDay.startOfDayMs(d + 1)), profile.heightCm.toDouble()
            )
            val estimates = Vo2Max.segmentEstimates(segments, rest, hrMax)
            if (estimates.isNotEmpty()) {
                val mean = estimates.map { it.first }.average()
                dao.upsert(Vo2EstimateEntity(d, passive?.value, mean, mean, mean * 0.2, estimates.size))
            }
        }

        val actives = ArrayList<Pair<Double, Double>>()
        sinceRows(today - 29).forEach { row ->
            val a = row.active ?: return@forEach
            repeat(row.activeSegments.coerceAtMost(8)) { actives += a to ACTIVE_RELATIVE_ERROR }
        }
        val combined = Vo2Max.combine(passive, actives) ?: return
        val own = dao.latest()?.takeIf { it.epochDay == today }
        dao.upsert(Vo2EstimateEntity(
            epochDay = today, passive = combined.passive, active = own?.active,
            value = combined.value, sigma = combined.sigma, activeSegments = own?.activeSegments ?: 0
        ))
    }

    private suspend fun sinceRows(fromDay: Long): List<Vo2EstimateEntity> = dao.sinceFlow(fromDay).first()

    private companion object {
        /** Used when a day's active estimates are read back without their individual errors. */
        const val ACTIVE_RELATIVE_ERROR = 0.18
    }
}
