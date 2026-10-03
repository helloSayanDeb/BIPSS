package com.bipss.app.ai

import org.junit.Assert.*
import org.junit.Test

class HistoryIntentTest {
    @Test fun naturalQuestionsRecognizeEveryHistoryTopic() {
        val examples = listOf(
            "Are my steps improving?", "How many days have I hit my step target?", "Show my best day for steps",
            "What is my average step count?", "Which workout was my longest?", "List the exercise I have done this week",
            "Has my activity changed versus the previous week?", "How many workouts have I done?",
            "Can you tell me what I have eaten today?", "Do my food entries have missing values?",
            "What is my total calories today?", "Tell me the protein total in my log",
            "Is my bedtime regular?", "Am I waking up at the same time?", "Has my sleep improved?",
            "How many nights of sleep are recorded?", "Was my sleep broken?", "Compare my actual sleep with my planned window",
            "Is my recovery improving?", "What is the count of my strain records?", "Show my strain trend",
            "Are my steps up to date?", "What does the band measure?", "Explain autonomic load please",
            "My sensor readings keep dropping out", "How is my habit progress?", "Can you review my weekly trends?",
            "Please summarize my period notes", "What is my VO₂ max?", "What is my fitness age?", "Did I get enough deep sleep?"
        )
        assertEquals(HistoryIntent.entries.size, examples.size)
        examples.forEachIndexed { i, q -> assertEquals(q, HistoryIntent.entries[i], HistoryIntent.match(q)) }
    }
    @Test fun sensitiveNotesRequirePersonalRequestAndHonorNegation() {
        assertTrue(HistoryCoach.isCycleRequest("Can you review my menstrual records?"))
        listOf("How does a menstrual cycle work?", "Don't show my cycle notes", "Do not summarize my period records", "Is cycling good for me?", "What is my exercise cycle?").forEach {
            assertFalse(it, HistoryCoach.isCycleRequest(it))
        }
    }
    @Test fun unrelatedQuestionsAndSubstringCollisionsDoNotTriggerHistory() {
        listOf("Can you write code?", "My doorstep needs paint", "My personality is unique", "What is the capital of France?", "What protein should I eat today?", "How many calories do I need?").forEach {
            assertNull(it, HistoryIntent.match(it))
        }
    }
    @Test fun questionsDoNotAuthorizeLogWritesAndDirectSymptomsKeepPriority() {
        val q = "How many workouts did I log?"
        assertNull(DailyCoach.activityCommand(q)); assertNull(DailyCoach.waterCommand(q)); assertNull(HistoryCoach.habitCommand(q))
        val urgent = "Compare my steps but I have chest pain and cannot breathe"
        assertTrue(HistoryCoach.isKnownRequest(urgent))
        assertTrue(SymptomCoach.respond(urgent, null)!!.text.contains("112"))
    }
    @Test fun todayGoalQuestionStaysWithDailyCoach() {
        assertNull(HistoryIntent.match("Have I reached my step goal today?"))
    }
}
