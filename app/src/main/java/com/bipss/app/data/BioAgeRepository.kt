package com.bipss.app.data

import com.bipss.app.data.db.dao.BioAgeDao
import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.dao.Vo2Dao
import com.bipss.app.data.db.entity.BioAgeEntity
import com.bipss.app.data.db.entity.DailySummaryEntity
import com.bipss.app.data.db.entity.Vo2EstimateEntity
import com.bipss.app.domain.bioage.BioAge
import com.bipss.app.domain.bioage.BioAgeInput
import com.bipss.app.domain.bioage.BioAgeResult
import com.bipss.app.domain.bioage.DayStats
import com.bipss.app.domain.bioage.PaceResult
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

fun DailySummaryEntity.toDayStats() = DayStats(
    epochDay = epochDay, validMinutes = validMinutes, sleepMinutes = sleepMinutes,
    sleepStartMin = sleepStartMin, sleepEndMin = sleepEndMin, restingHr = restingHr, steps = steps,
    zoneMin1 = zoneMin1, zoneMin2 = zoneMin2, zoneMin3 = zoneMin3, zoneMin45 = zoneMin45, strengthMin = strengthMin
)

/** Everything the BioAge screens show. */
data class BioAgeSnapshot(
    /** Null until the profile has an age. */
    val result: BioAgeResult?,
    val pace: PaceResult?,
    val vo2: Vo2EstimateEntity?,
    val history: List<BioAgeEntity>,
    val leanMassPct: Double?
)

class BioAgeRepository(
    private val summaries: DailySummaryDao,
    private val vo2Dao:    Vo2Dao,
    private val dao:       BioAgeDao,
    private val prefs:     UserPreferencesRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshot: Flow<BioAgeSnapshot> = LocalDay.midnightTicker().flatMapLatest { today ->
        combine(
            summaries.betweenFlow(today - BioAge.LONG_WINDOW_DAYS + 1, today),
            vo2Dao.latestFlow(), prefs.userProfile, prefs.leanMassPct,
            dao.sinceFlow(today - 365)
        ) { days, vo2, profile, lean, history ->
            val age = profile?.age?.takeIf { it in 18..110 }
            val input = age?.let {
                BioAgeInput(it, profile.gender.toBioSex(), days.map(DailySummaryEntity::toDayStats), today, vo2?.value, lean)
            }
            BioAgeSnapshot(input?.let(BioAge::compute), input?.let(BioAge::pace), vo2, history, lean)
        }
    }

    /** Keeps one history row for today, for the trend line and the weekly report. */
    suspend fun recordToday(nowMs: Long = System.currentTimeMillis()) {
        val s = snapshot.first()
        val result = s.result ?: return
        val bio = result.bioAge ?: return
        dao.upsert(BioAgeEntity(LocalDay.epochDay(nowMs), result.chronologicalAge, bio, s.pace?.pace))
    }
}
