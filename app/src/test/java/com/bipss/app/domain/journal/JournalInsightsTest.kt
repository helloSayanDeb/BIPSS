package com.bipss.app.domain.journal

import org.junit.Assert.*
import org.junit.Test

class JournalInsightsTest {
    /** Days 0..n-1 journalled; the outcome lands on the following day. */
    private fun setup(n: Int, yesWhen: (Int) -> Boolean, outcome: (Int, Boolean) -> Double):
        Pair<List<JournalAnswer>, Map<Long, Double>> {
        val answers = (0 until n).map { JournalAnswer(it.toLong(), Habit.ALCOHOL, yesWhen(it)) }
        val outcomes = (0 until n).associate { (it + 1).toLong() to outcome(it, yesWhen(it)) }
        return answers to outcomes
    }

    private fun alcohol(effects: List<HabitEffect>) = effects.first { it.habit == Habit.ALCOHOL }

    @Test fun aClearGapOverEnoughDaysIsReliable() {
        val (a, o) = setup(30, { it % 2 == 0 }) { i, yes -> (if (yes) 55.0 else 70.0) + (i % 3) }
        val e = alcohol(JournalInsights.effects(a, o))
        assertEquals(15, e.yesDays); assertEquals(15, e.noDays)
        assertTrue(e.reliable)
        assertTrue((e.difference ?: 0.0) < -10.0)
    }

    @Test fun tooFewDaysAreNeverReliable() {
        val (a, o) = setup(8, { it % 2 == 0 }) { _, yes -> if (yes) 40.0 else 80.0 }
        val e = alcohol(JournalInsights.effects(a, o))
        assertEquals(4, e.yesDays)
        assertFalse(e.reliable)
        assertNotNull(e.difference)
    }

    @Test fun noiseWithNoRealGapIsNotReliable() {
        val (a, o) = setup(40, { it % 2 == 0 }) { i, _ -> 60.0 + (i * 7 % 11) }
        assertFalse(alcohol(JournalInsights.effects(a, o)).reliable)
    }

    @Test fun daysWithoutAMorningScoreAreSkipped() {
        val answers = (0 until 10).map { JournalAnswer(it.toLong(), Habit.ALCOHOL, true) }
        val e = alcohol(JournalInsights.effects(answers, mapOf(1L to 50.0)))
        assertEquals(1, e.yesDays)
        assertNull(e.meanAfterNo)
        assertNull(e.difference)
    }

    @Test fun everyHabitIsReportedEvenWithNoAnswers() {
        val all = JournalInsights.effects(emptyList(), emptyMap())
        assertEquals(Habit.entries.size, all.size)
        assertTrue(all.none { it.reliable })
    }

    @Test fun keysRoundTrip() {
        Habit.entries.forEach { assertEquals(it, Habit.fromKey(it.key)) }
        assertNull(Habit.fromKey("nope"))
    }
}
