package com.bipss.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bipss.app.ble.protocol.*
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DailyActivityPersistenceTest {
    @Test fun insightSnapshotsSurviveRecreationAndKeepHistoricalInputs() = runBlocking {
        val store = MemoryStore()
        val repo = DailyActivityRepository(store)
        val score = com.bipss.app.domain.RiskScore(com.bipss.app.domain.RiskScoreType.HEAT_STRESS, 30,
            com.bipss.app.domain.RiskLevel.MEDIUM, emptyList(), computedAt = 1000,
            inputs = listOf(com.bipss.app.domain.InsightInput("ambient", "Temperature", 32.0, "°C")))
        repo.recordInsights(100, listOf(com.bipss.app.domain.InsightRecord.from(score)), 1000)
        repo.recordInsights(100, listOf(com.bipss.app.domain.InsightRecord.from(score.copy(score = 90))), 2000)
        assertEquals(30.0, repo.data.first().insights[100]!![score.type.name]!!.value!!, 0.0)
        val reopened = DailyActivityRepository(MemoryStore(store.data.first()))
        assertEquals(32.0, reopened.data.first().insights[100]!![score.type.name]!!.inputs.first().value!!, 0.0)
        reopened.recordInsights(101, listOf(com.bipss.app.domain.InsightRecord.from(score.copy(score = 50, computedAt = 3000))), 3000)
        assertEquals(30.0, reopened.data.first().insights[100]!![score.type.name]!!.value!!, 0.0)
        assertEquals(50.0, reopened.data.first().insights[101]!![score.type.name]!!.value!!, 0.0)
    }
    private class MemoryStore(initial: Preferences = emptyPreferences()): DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    @Test fun stepAnchorSurvivesRepositoryRecreationAndDuplicateDelivery() = runBlocking {
        val store = MemoryStore()
        val now = java.time.LocalDate.now().atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val day = LocalDay.epochDay(now)
        val event = BandStepEvent("band", BandSteps(1,60000,100), now)
        DailyActivityRepository(store).recordBandSteps(event)
        val reopened = DailyActivityRepository(MemoryStore(store.data.first()))
        reopened.recordBandSteps(event)
        reopened.recordBandSteps(event.copy(packet=BandSteps(1,65000,108),receivedMs=now+5000))
        assertEquals(108,reopened.data.first().bandSteps[day])
        assertTrue(reopened.data.first().steps.isEmpty())
    }
    @Test fun habitCompletionIsIdempotentUndoableAndPersisted() = runBlocking {
        val store=MemoryStore(); val repo=DailyActivityRepository(store)
        assertFalse(repo.markHabit(true))
        repo.setHabit("Stretch")
        repeat(2) { assertTrue(repo.markHabit(true)) }
        val reopened=DailyActivityRepository(MemoryStore(store.data.first()))
        assertEquals(1,reopened.data.first().weeklyHabit!!.completedDays.size)
        reopened.markHabit(false)
        assertTrue(reopened.data.first().weeklyHabit!!.completedDays.isEmpty())
        reopened.clearHabit(); assertNull(reopened.data.first().weeklyHabit)
    }
}
