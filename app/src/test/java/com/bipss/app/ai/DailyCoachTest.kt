package com.bipss.app.ai

import org.junit.Assert.*
import org.junit.Test

class DailyCoachTest {
    private val c = DailyCoach.Context(1200, 6000, 750, 2000, 500, 390, 70, 23f, 1380, 420, 900, null, null, null)
    @Test fun `answers use actual local records and do not invent missing scores`() {
        assertTrue(DailyCoach.respond("My steps", c)!!.text.contains("4800 steps to go"))
        assertTrue(DailyCoach.respond("My water goal", c)!!.text.contains("1250 mL"))
        assertTrue(DailyCoach.respond("water yesterday", c)!!.text.contains("500 mL yesterday"))
        assertTrue(DailyCoach.respond("how did I sleep", c)!!.text.contains("6h 30m"))
        assertTrue(DailyCoach.respond("run outside", c)!!.text.contains("don't have current"))
        assertTrue(DailyCoach.respond("My steps", c.copy(steps = null))!!.text.contains("No band steps"))
        assertNull(DailyCoach.respond("forecast my biological age", c))
    }
    @Test fun `followups preserve the selected topic`() {
        val first = DailyCoach.respond("My water goal", c)!!
        assertEquals(first, DailyCoach.respond("tell me more", c, first.topic))
        assertNull(DailyCoach.respond("tell me more", c))
    }
    @Test fun `only explicit bounded commands can write logs`() {
        assertEquals(250, DailyCoach.waterCommand("Log 250 mL water"))
        assertEquals("Walk" to 20, DailyCoach.activityCommand("Log a 20 minute walk"))
        listOf("Don't log 250 ml water", "Should I log 250 ml water?", "log 9000 ml water", "log 0 ml water")
            .forEach { assertNull(DailyCoach.waterCommand(it)) }
        listOf("Don't log a 20 minute walk", "log a 0 minute run", "log 999 minutes yoga", "I might log 20 minutes walk")
            .forEach { assertNull(DailyCoach.activityCommand(it)) }
    }
}
