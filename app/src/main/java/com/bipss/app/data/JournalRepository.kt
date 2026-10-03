package com.bipss.app.data

import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.dao.JournalDao
import com.bipss.app.data.db.entity.JournalEntryEntity
import com.bipss.app.domain.journal.Habit
import com.bipss.app.domain.journal.HabitEffect
import com.bipss.app.domain.journal.JournalAnswer
import com.bipss.app.domain.journal.JournalInsights
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** The daily journal: a few yes/no questions, and what the mornings after looked like. */
class JournalRepository(
    private val dao:       JournalDao,
    private val summaries: DailySummaryDao
) {
    /** The day's answers; a habit with no entry has not been answered. */
    fun answers(day: Long): Flow<Map<Habit, Boolean>> = dao.forDay(day).map { rows ->
        rows.mapNotNull { r -> Habit.fromKey(r.habit)?.let { it to r.yes } }.toMap()
    }

    /** [yes] null takes the answer back. */
    suspend fun set(day: Long, habit: Habit, yes: Boolean?) {
        if (yes == null) dao.clear(day, habit.key) else dao.upsert(JournalEntryEntity(day, habit.key, yes))
    }

    /** Days with at least one answer. */
    val daysAnswered: Flow<Int> = dao.allFlow().map { rows -> rows.map { it.epochDay }.toSet().size }

    @OptIn(ExperimentalCoroutinesApi::class)
    val effects: Flow<List<HabitEffect>> = LocalDay.midnightTicker().flatMapLatest { today ->
        combine(dao.allFlow(), summaries.betweenFlow(today - 400, today + 1)) { rows, days ->
            val answers = rows.mapNotNull { r -> Habit.fromKey(r.habit)?.let { JournalAnswer(r.epochDay, it, r.yes) } }
            val recovery = days.mapNotNull { d -> d.recovery?.let { d.epochDay to it.toDouble() } }.toMap()
            JournalInsights.effects(answers, recovery)
        }
    }
}
