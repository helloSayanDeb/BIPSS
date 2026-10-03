package com.bipss.app.data

import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.domain.sleep.PlannerNight
import com.bipss.app.domain.sleep.SleepPlan
import com.bipss.app.domain.sleep.SleepPlanner
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

/** Tonight's plan and the recent nights it was worked out from. */
data class SleepPlanState(
    val plan: SleepPlan,
    /** Oldest first, the last two weeks. */
    val nights: List<PlannerNight>,
    val strainUsed: Double?
)

class SleepPlanRepository(
    private val summaries: DailySummaryDao,
    private val daily:     DailyActivityRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<SleepPlanState> = LocalDay.midnightTicker().flatMapLatest { today ->
        combine(summaries.betweenFlow(today - 20, today), daily.data) { rows, data ->
            build(rows.map { PlannerNight(it.epochDay, it.sleepMinutes ?: 0) }.filter { it.sleepMinutes >= 120 },
                data.health[today]?.strain ?: data.health[today - 1]?.strain, data.wakeMinutes)
        }
    }

    suspend fun current(): SleepPlanState = state.first()

    private fun build(nights: List<PlannerNight>, strain: Float?, wakeMin: Int): SleepPlanState {
        val s = strain?.toDouble()
        return SleepPlanState(SleepPlanner.plan(nights, s, wakeMin), nights.takeLast(14), s)
    }
}
