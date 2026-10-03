package com.bipss.app.data

import com.bipss.app.data.db.dao.WaterLogDao
import com.bipss.app.data.db.dao.WaterTotals
import com.bipss.app.data.db.entity.WaterLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WaterCorrectionTest {
    private class MemoryDao(entries: List<WaterLogEntity>) : WaterLogDao {
        val rows = entries.toMutableList()
        override suspend fun insert(entry: WaterLogEntity) { rows.add(entry) }
        override suspend fun entriesBetween(startMs: Long, endMs: Long) = rows.filter { it.timestampMs in startMs until endMs }
            .sortedWith(compareByDescending<WaterLogEntity> { it.timestampMs }.thenByDescending { it.id })
        override fun entriesBetweenFlow(startMs: Long, endMs: Long): Flow<List<WaterLogEntity>> = flowOf(rows.toList())
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun updateAmount(id: Long, amountMl: Int) { val i = rows.indexOfFirst { it.id == id }; rows[i] = rows[i].copy(amountMl = amountMl) }
        override suspend fun totalsBetween(startMs: Long, endMs: Long): WaterTotals = entriesBetween(startMs, endMs).let { WaterTotals(it.size, it.sumOf { row -> row.amountMl }) }
        override fun totalsBetweenFlow(startMs: Long, endMs: Long): Flow<WaterTotals> = flowOf(WaterTotals(0, 0))
        override fun lastLogMsFlow(): Flow<Long?> = flowOf(rows.maxOfOrNull { it.timestampMs })
        override suspend fun lastLogMs(): Long? = rows.maxOfOrNull { it.timestampMs }
        override suspend fun all() = rows.toList()
        override suspend fun pruneOlderThan(olderThanMs: Long) { rows.removeAll { it.timestampMs < olderThanMs } }
    }

    @Test fun `minus corrects newest entries and preserves original timestamps`() = runBlocking {
        val dao = MemoryDao(listOf(WaterLogEntity(1, 110, 500), WaterLogEntity(2, 120, 100)))
        dao.subtractBetween(100, 200, 250)
        assertEquals(listOf(WaterLogEntity(1, 110, 350)), dao.rows)
        assertEquals(110L, dao.lastLogMs())
    }

    @Test fun `minus clamps to zero and never subtracts from yesterday or tomorrow`() = runBlocking {
        val dao = MemoryDao(listOf(WaterLogEntity(1, 99, 500), WaterLogEntity(2, 100, 100), WaterLogEntity(3, 200, 500)))
        repeat(3) { dao.subtractBetween(100, 200, 250) }
        assertEquals(listOf(1L, 3L), dao.rows.map { it.id })
        assertEquals(0, dao.totalsBetween(100, 200).totalMl)
    }

    @Test fun `equal timestamps use most recent row and delete reverts last drink`() = runBlocking {
        val dao = MemoryDao(listOf(WaterLogEntity(1, 100, 250), WaterLogEntity(2, 100, 250)))
        dao.subtractBetween(100, 200, 250)
        assertEquals(listOf(1L), dao.rows.map { it.id })
        dao.subtractBetween(100, 200, 250)
        assertNull(dao.lastLogMs())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative correction is rejected`() = runBlocking { MemoryDao(emptyList()).subtractBetween(100, 200, -250) }
}
