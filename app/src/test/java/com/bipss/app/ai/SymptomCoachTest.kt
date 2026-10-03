package com.bipss.app.ai

import org.junit.Assert.*
import org.junit.Test

class SymptomCoachTest {
    @Test fun `dizziness gets steps before any reading summary`() {
        for (q in listOf("I feel dizzy", "I am feeling dizy ness", "dizziness", "light-headed", "feeling faint")) {
            val result = TemplatedResponder.respond(q, emptyList(), 70, 98, 33f)
            assertTrue(q, result.startsWith("Sit or lie down"))
            assertTrue(result.contains("When did it start"))
            assertFalse(result.contains("70"))
            assertFalse(result.contains("within expected range"))
            assertFalse(result.contains("everything looks"))
        }
    }

    @Test fun `dizziness works without band or scores`() {
        assertTrue(TemplatedResponder.respond("dizzy", emptyList(), null, null, null).contains("Sit or lie down"))
    }

    @Test fun `emergency overrides request for normal oxygen`() {
        assertTrue(TemplatedResponder.respond("I can't breathe but oxygen is 98", emptyList(), 70, 98, null).contains("now"))
    }

    @Test fun `combined symptoms receive immediate escalation`() {
        val reply = SymptomCoach.respond("I have chest pain and I am dizzy")!!.text
        assertTrue(reply.contains("Call 112"))
        assertFalse(reply.contains("When did"))
    }

    @Test fun `negated chest pain does not mask actual dizziness`() {
        for (q in listOf("No chest pain, but dizzy", "I am dizzy but no chest pain", "I don't have chest pain but feel dizzy")) {
            assertTrue(q, SymptomCoach.respond(q)!!.text.startsWith("Sit or lie down"))
        }
    }

    @Test fun `negated symptoms do not trigger an emergency`() {
        for (q in listOf("not dizzy", "no chest pain or shortness of breath", "no confusion", "dizziness has gone")) {
            val reply = SymptomCoach.respond(q)
            assertFalse(q, reply?.text?.contains("Call 112") == true)
        }
    }

    @Test fun `not sure is not a negated symptom`() {
        assertEquals(SymptomCoach.Topic.DIZZINESS, SymptomCoach.respond("not sure why I am dizzy")!!.topic)
    }

    @Test fun `negating one symptom does not negate a later emergency`() {
        assertTrue(SymptomCoach.respond("I'm not dizzy but cannot breathe")!!.text.contains("Call 112"))
    }

    @Test fun `headache negation is respected`() {
        assertFalse(SymptomCoach.respond("my headache is not sudden or severe")!!.text.contains("Call 112"))
        assertTrue(SymptomCoach.respond("sudden severe headache")!!.text.contains("Call 112"))
    }

    @Test fun `fever does not use skin temperature as a thermometer`() {
        val reply = TemplatedResponder.respond("do I have a fever my temperature is high", emptyList(), null, null, 34f)
        assertTrue(reply.contains("thermometer"))
        assertFalse(reply.contains("34"))
    }

    @Test fun `brief follow up retains symptom context`() {
        val initial = SymptomCoach.respond("I feel dizzy")!!
        val next = SymptomCoach.respond("10 minutes since I stood up", initial.topic)!!
        assertTrue(next.text.contains("better, the same, or worse"))
        assertEquals(initial.topic, next.topic)
        assertTrue(SymptomCoach.respond("it is getting worse", next.topic)!!.text.contains("medical advice promptly"))
    }

    @Test fun `unrelated question leaves symptom flow`() {
        assertNull(SymptomCoach.respond("what is my heart rate", SymptomCoach.Topic.DIZZINESS))
        assertNull(SymptomCoach.respond("how much water today", SymptomCoach.Topic.DIZZINESS))
    }

    @Test fun `ordinary metric question still reports available measurement`() {
        assertEquals("Your current heart rate is 72 bpm.", TemplatedResponder.respond("HR?", emptyList(), 72, null, null))
    }

    @Test fun `water response uses actual local total and current controls`() {
        val reply = TemplatedResponder.respond("water today?", emptyList(), null, null, null, 750)
        assertTrue(reply.contains("750 mL today"))
        assertTrue(reply.contains("250 mL"))
        assertTrue(reply.contains("correct"))
    }

    @Test fun `unknown question asks for detail instead of dumping vitals`() {
        val reply = TemplatedResponder.respond("can you help me plan something", emptyList(), 72, 98, 33f)
        assertTrue(reply.contains("what you're feeling"))
        assertFalse(reply.contains("72"))
    }

    @Test fun `confusion about a feature is not treated as a medical emergency`() {
        assertNull(SymptomCoach.respond("I am confused about my water goal"))
        assertTrue(SymptomCoach.respond("I am suddenly confused")!!.text.contains("Call 112"))
    }
}
