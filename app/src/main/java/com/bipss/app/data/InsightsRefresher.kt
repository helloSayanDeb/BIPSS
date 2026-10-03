package com.bipss.app.data

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings the derived figures up to date: the day's summary, newly detected
 * workouts, the VO2 max estimate and BioAge. Runs from the background job,
 * and when the app is opened (at most every ten minutes).
 */
class InsightsRefresher(
    private val summaries: DailySummaryRepository,
    private val workouts:  WorkoutRepository,
    private val vo2:       Vo2Repository,
    private val bioAge:    BioAgeRepository
) {
    private val lock = Mutex()
    @Volatile private var lastRunMs = 0L
    @Volatile private var backfilled = false

    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunMs < MIN_INTERVAL_MS) return
        lastRunMs = now
        step("summary") {
            if (!backfilled) { summaries.backfill(); backfilled = true }
            summaries.refresh()
        }
        step("workouts") { workouts.detectRecent() }
        // The workouts above may add confirmed minutes only after review, but
        // the summary read zone minutes already, so VO2 max follows it.
        step("vo2") { vo2.refresh() }
        step("bioage") { bioAge.recordToday() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Refresh step $name failed", e) }
    }

    private companion object {
        const val TAG = "InsightsRefresher"
        const val MIN_INTERVAL_MS = 10 * 60_000L
    }
}
