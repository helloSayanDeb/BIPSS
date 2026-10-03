package com.bipss.app.data

import com.bipss.app.data.db.dao.WaterLogDao
import com.bipss.app.data.db.dao.WaterTotals
import com.bipss.app.data.db.entity.WaterLogEntity
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Logged drinks, bucketed by the device's local calendar day.
 *
 * "Today" and "yesterday" are queries between local-midnight bounds from
 * [LocalDay], and the today-total re-subscribes at every local midnight so the
 * card rolls over at 00:00 on its own rather than at 05:30 IST (the old UTC
 * epoch-day bug) or whenever some unrelated write happened to occur.
 */
class WaterRepository(
    private val dao:   WaterLogDao,
    private val prefs: UserPreferencesRepository
) {
    suspend fun log(amountMl: Int, timestampMs: Long = System.currentTimeMillis()) {
        require(amountMl in 1..5000) { "A drink must be between 1 and 5000 mL" }
        dao.insert(WaterLogEntity(timestampMs = timestampMs, amountMl = amountMl))
    }

    suspend fun subtractToday(amountMl: Int = 250, nowMs: Long = System.currentTimeMillis()) {
        val range = LocalDay.dayRangeMs(nowMs)
        dao.subtractBetween(range.first, range.last + 1, amountMl)
    }

    suspend fun remove(id: Long) = dao.deleteById(id)

    fun entriesBetween(startMs: Long, endMs: Long): Flow<List<WaterLogEntity>> =
        dao.entriesBetweenFlow(startMs, endMs)

    /** Totals for the current local day; re-emits after local midnight. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todayTotals: Flow<WaterTotals> = LocalDay.midnightTicker().flatMapLatest { day ->
        dao.totalsBetweenFlow(LocalDay.startOfDayMs(day), LocalDay.startOfDayMs(day + 1))
    }

    val lastLogMs: Flow<Long?> = dao.lastLogMsFlow()

    suspend fun yesterdayTotals(nowMs: Long = System.currentTimeMillis()): WaterTotals {
        val range = LocalDay.previousDayRangeMs(nowMs)
        return dao.totalsBetween(range.first, range.last + 1)
    }

    /**
     * Hours since the last logged drink, or **null when nothing has ever been
     * logged**. Not having logged a drink is not evidence of not having drunk
     * one, so "never" stays unknown and the hydration component drops out of
     * the Heat Stress score rather than defaulting to a constant.
     */
    suspend fun hoursSinceLastLog(nowMs: Long = System.currentTimeMillis()): Float? {
        val last = dao.lastLogMs() ?: return null
        return ((nowMs - last) / 3_600_000f).coerceIn(0f, 24f)
    }

    /**
     * One-time move of the old DataStore counter into the table. The counter
     * only ever held one day (count, total mL, time of the last drink); those
     * drinks are recorded at the one time that was actually stored, so today's
     * count, total and "last drink" are preserved without inventing times.
     */
    suspend fun migrateLegacyCounter() {
        val legacy = prefs.consumeLegacyWaterCounter() ?: return
        if (legacy.count <= 0 || legacy.totalMl <= 0) return
        val base = legacy.totalMl / legacy.count
        repeat(legacy.count) { i ->
            val amount = if (i == legacy.count - 1) legacy.totalMl - base * (legacy.count - 1) else base
            dao.insert(WaterLogEntity(timestampMs = legacy.lastLogMs, amountMl = amount))
        }
    }
}
