package com.bipss.app.ai

import com.bipss.app.data.*
import org.junit.Assert.*
import org.junit.Test

class HistoryCoachTest {
    private val context = HistoryCoach.Context(100, DailyActivityData(), emptyList())
    @Test fun all25QuestionsHaveOfflineMissingDataAnswers() {
        assertEquals(25, HistoryCoach.questions.distinct().size)
        HistoryCoach.questions.forEach { assertNotNull(it, HistoryCoach.respond(it,context)) }
    }
    @Test fun questionMatchingIsPunctuationInsensitive() {
        assertNotNull(HistoryCoach.respond("HOW CONSISTENT IS MY SLEEP SCHEDULE",context))
    }
    @Test fun selectedQuestionsStartANewTopic() {
        HistoryCoach.questions.forEach { assertTrue(HistoryCoach.isKnownRequest(it)) }
        assertFalse(HistoryCoach.isKnownRequest("I've been dizzy for 3 days"))
        assertNotNull(SymptomCoach.respond("I've been dizzy for 3 days"))
    }
    @Test fun midnightDoesNotCreateA24HourVariation() {
        assertEquals(10,HistoryCoach.timingVariation(listOf(1425,0,15)))
        assertEquals(10,HistoryCoach.timingVariation(listOf(585,600,615)))
        assertNull(HistoryCoach.timingVariation(listOf(0,15)))
    }
    @Test fun missingDaysAreNotZeroInComparisons() {
        val few=mapOf(99L to 900.0,98L to 600.0,90L to 100.0)
        assertTrue(HistoryCoach.compare("Steps",few,100,"steps").contains("2/7"))
        assertTrue(HistoryCoach.compare("Steps",few,100,"steps").contains("at least 3"))
        val enough = few + mapOf(97L to 300.0,89L to 200.0,88L to 300.0,100L to 99999.0)
        val answer=HistoryCoach.compare("Steps",enough,100,"steps")
        assertTrue(answer.contains("600.0")); assertTrue(answer.contains("200.0"))
        assertFalse(answer.contains("99999"))
    }
    @Test fun habitCommandsRequireAnExplicitAnchoredRequest() {
        assertEquals("Walk for 10 minutes",HistoryCoach.habitCommand("Set my weekly habit: Walk for 10 minutes"))
        assertNull(HistoryCoach.habitCommand("Don't set my weekly habit: Run"))
        assertNull(HistoryCoach.habitCommand("Could I set my weekly habit: Walk"))
    }
    @Test fun weeklyHabitCountsOnlyCurrentPeriod() {
        val data=DailyActivityData(weeklyHabit=WeeklyHabit("Stretch",90,setOf(90,97,99)))
        assertTrue(HistoryCoach.respond("My weekly habit",context.copy(data=data))!!.contains("2 days"))
    }
    @Test fun disabledFoodJournalDoesNotDisclosePriorEntries() {
        val d=DailyActivityData(foods=listOf(FoodEntry(atMs=0,name="Private meal",calories=600)))
        val answer=HistoryCoach.respond(HistoryCoach.questions[8],context.copy(data=d))!!
        assertFalse(answer.contains("Private meal"))
    }
    @Test fun cycleRequestsNeverGoThroughGeneralHistoryContext() {
        assertNull(HistoryCoach.respond("My cycle notes",context))
    }
}
