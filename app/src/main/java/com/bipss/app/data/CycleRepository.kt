package com.bipss.app.data

import com.bipss.app.data.db.dao.CycleDao
import com.bipss.app.data.db.entity.CycleDayNoteEntity
import com.bipss.app.data.db.entity.CyclePeriodEntity
import com.bipss.app.domain.cycle.CyclePredictor
import com.bipss.app.domain.cycle.Period
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Period tracking (spec 7): logged periods and per-day notes, in the app's own
 * database - no calendar permission, nothing shared. Logging is checked
 * against what makes sense (CyclePredictor.startConflict / endConflict) and a
 * refusal comes back as a sentence for the screen, never an exception.
 */
class CycleRepository(private val dao: CycleDao) {

    val periods: Flow<List<Period>> = dao.periods().map { rows -> rows.map { it.toPeriod() } }

    fun notesBetween(fromDay: Long, toDay: Long): Flow<Map<Long, String>> =
        dao.notesBetween(fromDay, toDay).map { rows -> rows.associate { it.epochDay to it.note } }

    /** Null on success, else why it was refused. */
    suspend fun logStart(day: Long, todayDay: Long = LocalDay.epochDay(System.currentTimeMillis())): String? {
        val periods = dao.periodsNow().map { it.toPeriod() }
        CyclePredictor.startConflict(periods, day, todayDay)?.let { return it }
        dao.insertPeriod(CyclePeriodEntity(startEpochDay = day, endEpochDay = null))
        return null
    }

    /** Null on success, else why it was refused. */
    suspend fun logEnd(day: Long, todayDay: Long = LocalDay.epochDay(System.currentTimeMillis())): String? {
        val periods = dao.periodsNow().map { it.toPeriod() }
        CyclePredictor.endConflict(periods, day, todayDay)?.let { return it }
        val target = CyclePredictor.periodToEnd(periods, day) ?: return "Log the start of this period first."
        dao.updatePeriod(CyclePeriodEntity(id = target.id, startEpochDay = target.startDay, endEpochDay = day))
        return null
    }

    suspend fun removePeriod(id: Long) = dao.deletePeriod(id)

    /** Saves a note; an empty one removes the day's note. */
    suspend fun saveNote(day: Long, text: String) {
        if (text.isBlank()) dao.deleteNote(day)
        else dao.upsertNote(CycleDayNoteEntity(day, text.trim(), System.currentTimeMillis()))
    }

    suspend fun note(day: Long): String? = dao.note(day)?.note

    private fun CyclePeriodEntity.toPeriod() = Period(id, startEpochDay, endEpochDay)
}
