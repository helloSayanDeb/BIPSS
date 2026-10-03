package com.bipss.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.bipss.app.domain.DailyWellness
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable data class ActivityEntry(val id: String = UUID.randomUUID().toString(), val atMs: Long,
    val name: String, val minutes: Int, val note: String = "", val startMs: Long? = null)
@Serializable data class WeeklyHabit(val name: String, val startDay: Long, val completedDays: Set<Long> = emptySet())
@Serializable data class ActiveWorkout(val name: String, val startMs: Long)
@Serializable data class DayHealth(val sleep: Int? = null, val recovery: Int? = null, val strain: Float? = null)
@Serializable data class FoodEntry(val id: String = UUID.randomUUID().toString(), val atMs: Long,
    val name: String, val calories: Int? = null, val proteinG: Int? = null)
@Serializable data class DailyActivityData(
    val steps: Map<Long, Int> = emptyMap(), val goal: Int = 6000,
    val activities: List<ActivityEntry> = emptyList(),
    val lastCounter: Long? = null, val lastDay: Long? = null, val boot: Int? = null,
    val trackingEnabled: Boolean = false,
    val health: Map<Long, DayHealth> = emptyMap(),
    val foods: List<FoodEntry> = emptyList(),
    val nutritionEnabled: Boolean = false,
    val bedtimeMinutes: Int = 23 * 60,
    val wakeMinutes: Int = 7 * 60,
    val activeWorkout: ActiveWorkout? = null,
    val weeklyHabit: WeeklyHabit? = null,
    val bandSteps: Map<Long, Int> = emptyMap(),
    val bandStepAnchor: com.bipss.app.ble.protocol.BandStepAnchor? = null,
    val insights: Map<Long, Map<String, com.bipss.app.domain.InsightRecord>> = emptyMap()
)

class DailyActivityRepository(private val store: DataStore<Preferences>) {
    private var lastInsightWriteMs = 0L
    private var lastInsightDay: Long? = null
    private val insightMutex = kotlinx.coroutines.sync.Mutex()
    private val key = stringPreferencesKey("daily_activity_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun read(p: Preferences): DailyActivityData = p[key]?.let {
        runCatching { json.decodeFromString<DailyActivityData>(it) }.getOrNull()
    } ?: DailyActivityData()
    val data: Flow<DailyActivityData> = store.data.map(::read).distinctUntilChanged()
    private suspend fun update(block: (DailyActivityData) -> DailyActivityData) {
        store.edit { it[key] = json.encodeToString(block(read(it))) }
    }
    suspend fun setHabit(name: String) {
        require(name.length in 3..80)
        update { it.copy(weeklyHabit = WeeklyHabit(name, LocalDay.epochDay(System.currentTimeMillis()))) }
    }
    suspend fun markHabit(done: Boolean): Boolean {
        var exists = false
        val day = LocalDay.epochDay(System.currentTimeMillis())
        update { old -> old.weeklyHabit?.let { h ->
            exists = true
            old.copy(weeklyHabit = h.copy(completedDays = (if (done) h.completedDays + day else h.completedDays - day).filter { it >= day - 89 }.toSet()))
        } ?: old }
        return exists
    }
    suspend fun clearHabit() = update { it.copy(weeklyHabit = null) }
    /** Adds the packet to today count and returns the steps it added (null for a duplicate or reset). */
    suspend fun recordBandSteps(event: com.bipss.app.ble.protocol.BandStepEvent): Int? {
        val day = LocalDay.epochDay(event.receivedMs)
        val midnight = java.time.LocalDate.ofEpochDay(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        var added: Int? = null
        update { old ->
            val p = event.packet
            val delta = com.bipss.app.ble.protocol.BandStepAccounting.delta(old.bandStepAnchor, event.device,
                p, day, event.receivedMs, event.receivedMs - midnight)
            if (delta == null) old else {
                added = delta.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                old.copy(
                    bandSteps = old.bandSteps.filterKeys { it >= day - 89 } +
                        (day to ((old.bandSteps[day] ?: 0).toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    bandStepAnchor = com.bipss.app.ble.protocol.BandStepAnchor(event.device, p.bootId, p.uptimeMs,
                        p.total, day, event.receivedMs))
            }
        }
        return added
    }
    suspend fun setTracking(enabled: Boolean) = update {
        it.copy(trackingEnabled = enabled, lastCounter = null, lastDay = null, boot = null)
    }
    suspend fun setGoal(goal: Int) { require(goal in 1000..30000); update { it.copy(goal = goal) } }
    suspend fun recordCounter(counter: Long, boot: Int, nowMs: Long = System.currentTimeMillis()) {
        if (counter < 0L) return
        val today = LocalDay.epochDay(nowMs)
        update { old ->
            if (!old.trackingEnabled) old else {
                val delta = DailyWellness.stepDelta(old.lastCounter, counter, old.lastDay == today, old.boot == boot)
                val count = ((old.steps[today] ?: 0).toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                old.copy(steps = old.steps.filterKeys { it >= today - 89 } + (today to count),
                    lastCounter = counter, lastDay = today, boot = boot)
            }
        }
    }
    suspend fun logActivity(name: String, minutes: Int, note: String) {
        require(name in listOf("Walk", "Run", "Cycle", "Workout", "Yoga")); require(minutes in 1..600)
        val now = System.currentTimeMillis()
        update { it.copy(activities = (it.activities + ActivityEntry(atMs = now, name = name,
            minutes = minutes, note = note.take(300))).filter { entry -> entry.atMs >= now - 90L * 86_400_000 }.takeLast(500)) }
    }
    suspend fun deleteActivity(id: String) = update { it.copy(activities = it.activities.filterNot { entry -> entry.id == id }) }
    suspend fun recordHealth(day: Long, health: DayHealth) = update { old ->
        val previous = old.health[day]
        val next = DayHealth(health.sleep ?: previous?.sleep, health.recovery ?: previous?.recovery,
            health.strain ?: previous?.strain)
        old.copy(health = old.health.filterKeys { it >= day - 89 } + (day to next))
    }
    suspend fun setSleepTimes(bed: Int, wake: Int) {
        require(bed in 0..1439 && wake in 0..1439)
        update { it.copy(bedtimeMinutes = bed, wakeMinutes = wake) }
    }
    /** At most one checkpoint per minute; live details use the current calculation. */
    suspend fun recordInsights(day: Long, records: List<com.bipss.app.domain.InsightRecord>, nowMs: Long = System.currentTimeMillis()) {
        insightMutex.lock()
        try {
            if (day == lastInsightDay && nowMs >= lastInsightWriteMs && nowMs - lastInsightWriteMs < 60_000) return
            update { it.copy(insights = com.bipss.app.domain.mergeInsightRecords(it.insights, day, records)) }
            lastInsightWriteMs = nowMs
            lastInsightDay = day
        } finally { insightMutex.unlock() }
    }
    suspend fun enableNutrition(enabled: Boolean) = update { it.copy(nutritionEnabled = enabled) }
    suspend fun logFood(name: String, calories: Int?, proteinG: Int?) {
        require(name.isNotBlank() && name.length <= 100)
        require(calories == null || calories in 0..10000); require(proteinG == null || proteinG in 0..1000)
        val now = System.currentTimeMillis()
        update { it.copy(foods = (it.foods + FoodEntry(atMs = now, name = name, calories = calories, proteinG = proteinG))
            .filter { entry -> entry.atMs >= now - 90L * 86_400_000 }.takeLast(1000)) }
    }
    suspend fun deleteFood(id: String) = update { it.copy(foods = it.foods.filterNot { entry -> entry.id == id }) }
    suspend fun startWorkout(name: String) {
        require(name in listOf("Walk", "Run", "Cycle", "Workout", "Yoga"))
        update { if (it.activeWorkout != null) it else it.copy(activeWorkout = ActiveWorkout(name, System.currentTimeMillis())) }
    }
    suspend fun finishWorkout() = update { old ->
        old.activeWorkout?.let { active ->
            val now = System.currentTimeMillis()
            old.copy(activeWorkout = null, activities = (old.activities + ActivityEntry(atMs = now,
                name = active.name, minutes = ((now - active.startMs).coerceAtLeast(0) / 60_000).toInt(), startMs = active.startMs)).takeLast(500))
        } ?: old
    }
    suspend fun cancelWorkout() = update { it.copy(activeWorkout = null) }
}
