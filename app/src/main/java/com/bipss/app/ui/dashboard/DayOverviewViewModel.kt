package com.bipss.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.*
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.data.db.entity.WaterLogEntity
import com.bipss.app.data.db.dao.MetricStats
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.ui.metric.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay

data class DayOverview(
    val day: Long = LocalDay.epochDay(System.currentTimeMillis()),
    val isToday: Boolean = true,
    val sleep: SleepSessionEntity? = null,
    val health: DayHealth = DayHealth(),
    val activity: DailyActivityData = DailyActivityData(),
    val water: List<WaterLogEntity> = emptyList(),
    val vitals: Map<String, MetricStats> = emptyMap(),
    val heartTrace: List<GraphPoint> = emptyList(),
    val loading: Boolean = true
) {
    val activities get() = activity.activities.filter { LocalDay.epochDay(it.atMs) == day }
    val steps get() = activity.bandSteps[day]
    val waterMl get() = water.sumOf { it.amountMl }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DayOverviewViewModel(private val db: AppDatabase, private val daily: DailyActivityRepository) : ViewModel() {
    private val selection = MutableStateFlow<Long?>(null)
    private val days = combine(selection, LocalDay.midnightTicker()) { selected, today -> (selected ?: today) to today }
    private val ticks = flow { while (true) { emit(Unit); delay(30_000) } }
    val ui = days.flatMapLatest { (day, today) ->
        val start = LocalDay.startOfDayMs(day)
        val end = LocalDay.startOfDayMs(day + 1)
        combine(daily.data, db.sleepSessionDao().allFlow(), db.waterLogDao().entriesBetweenFlow(start, end), ticks) { data, sleeps, water, _ ->
            val sleep = sleeps.filter { LocalDay.epochDay(it.endMs) == day }.maxByOrNull { it.totalSleepMs }
            val vitals = mutableMapOf<String, MetricStats>()
            var trace = emptyList<GraphPoint>()
            for (metric in MetricTypes.displayable) {
                val buckets = mergeBuckets(db.rawReadingDao().getBuckets(metric, start, end, 300_000),
                    db.minuteAggregateDao().getBuckets(metric, start / 60_000, end / 60_000, 300_000))
                val stats = combineStats(buckets)
                if (stats.sampleCount > 0) vitals[metric] = stats
                if (metric == MetricTypes.HR) trace = insertGaps(bucketsToPoints(buckets, 300_000), 600_000)
            }
            val sleepScore = sleep?.let { SleepRepository.score(it) }
            DayOverview(day, day == today, sleep, (data.health[day] ?: DayHealth()).copy(sleep = sleepScore),
                data, water, vitals, trace, loading = false)
        }.onStart { emit(DayOverview(day = day, isToday = day == today)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayOverview())

    fun select(day: Long) {
        val today = LocalDay.epochDay(System.currentTimeMillis())
        selection.value = day.coerceIn(today - 89, today).takeUnless { it == today }
    }
}
