package com.bipss.app.data

import com.bipss.app.data.db.dao.DetectedWorkoutDao
import com.bipss.app.data.db.entity.DetectedWorkoutEntity
import com.bipss.app.domain.strain.Trimp
import com.bipss.app.domain.strain.TrimpSex
import com.bipss.app.domain.workout.WorkoutDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The activity types a detected effort can be named as; the stored value is always the English key. */
val WORKOUT_TYPES = listOf("Walk", "Run", "Cycle", "Strength", "Workout", "Yoga")

/**
 * Finds sustained efforts in the last day and a half of band data and keeps
 * them for the user to name or dismiss. An effort that overlaps something the
 * user already logged is not offered again, and one still in progress is left
 * until it has ended.
 */
class WorkoutRepository(
    private val minutes: MinuteData,
    private val dao:     DetectedWorkoutDao,
    private val scoring: ScoringRepository,
    private val prefs:   UserPreferencesRepository,
    private val daily:   DailyActivityRepository
) {
    val pending: Flow<List<DetectedWorkoutEntity>> = dao.pending()
    fun recent(limit: Int = 20): Flow<List<DetectedWorkoutEntity>> = dao.recent(limit)

    suspend fun confirm(id: Long, type: String) {
        require(type in WORKOUT_TYPES)
        dao.confirm(id, type)
    }

    suspend fun dismiss(id: Long) = dao.dismiss(id)

    suspend fun detectRecent(nowMs: Long = System.currentTimeMillis(), lookbackMs: Long = 36 * 3_600_000L): Int {
        val profile = prefs.userProfile.first() ?: return 0
        val age = profile.age.takeIf { it in 10..110 } ?: return 0
        val start = (nowMs - lookbackMs) / 60_000L * 60_000L
        val rest = scoring.restingHrOrFallback(start, nowMs) ?: return 0
        val sex = when (profile.gender) {
            Gender.MALE -> TrimpSex.MALE
            Gender.FEMALE -> TrimpSex.FEMALE
            else -> TrimpSex.UNSPECIFIED
        }
        val efforts = WorkoutDetector.detect(minutes.workMinutes(start, nowMs), rest, Trimp.gellishHrMax(age), sex)
        val logged = daily.data.first().activities.map { (it.startMs ?: (it.atMs - it.minutes * 60_000L)) to it.atMs }
        var added = 0
        for (e in efforts) {
            // Still going: the end would move, and the row is keyed on the start.
            if (e.endMs > nowMs - 3 * 60_000L) continue
            if (logged.any { (s, t) -> s < e.endMs && t > e.startMs }) continue
            if (dao.overlapping(e.startMs, e.endMs).isNotEmpty()) continue
            val id = dao.insert(DetectedWorkoutEntity(
                startMs = e.startMs, endMs = e.endMs, minutes = e.minutes, meanHr = e.meanHr, maxHr = e.maxHr,
                meanHrr = e.meanHrr, trimp = e.trimp, cadence = e.cadence,
                guessedType = e.guessedType, type = e.guessedType.let { if (it == "WORKOUT") "Workout" else it.lowercase().replaceFirstChar(Char::uppercase) },
                status = "NEW"
            ))
            if (id > 0) added++
        }
        return added
    }
}
