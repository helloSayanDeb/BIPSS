package com.bipss.app.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosMessageBuilderTest {

    @Test
    fun `fresh precise fix is stated plainly`() {
        val clause = SosMessageBuilder.locationClause(SosLocation(22.5726, 88.3639))
        assertEquals("Location: https://maps.google.com/?q=22.572600,88.363900.", clause)
    }

    @Test
    fun `reused fix is labelled last known and approximate when coarse`() {
        val clause = SosMessageBuilder.locationClause(SosLocation(1.0, 2.0, isLastKnown = true, isApproximate = true))
        assertTrue(clause, clause.startsWith("Last known location (approximate): "))
    }

    @Test
    fun `reused fix states its age`() {
        val clause = SosMessageBuilder.locationClause(SosLocation(1.0, 2.0, isLastKnown = true, ageMinutes = 185))
        assertTrue(clause, clause.startsWith("Last known location (about 3 h old): "))
        val both = SosMessageBuilder.locationClause(
            SosLocation(1.0, 2.0, isLastKnown = true, isApproximate = true, ageMinutes = 12)
        )
        assertTrue(both, both.startsWith("Last known location (approximate, about 12 min old): "))
    }

    @Test
    fun `a fresh fix never carries an age`() {
        val clause = SosMessageBuilder.locationClause(SosLocation(1.0, 2.0, ageMinutes = 5))
        assertTrue(clause, clause.startsWith("Location: "))
    }

    @Test
    fun `no fix says so instead of omitting it`() {
        assertEquals("Location unavailable.", SosMessageBuilder.locationClause(null))
    }

    @Test
    fun `manual SOS does not claim a fall happened`() {
        val msg = SosMessageBuilder.contactMessage("Asha", "28 Sep 2026, 07:00 IST", null)
        assertFalse(msg.contains("fall", ignoreCase = true))
        assertTrue(msg.startsWith("EMERGENCY: Asha triggered an SOS alert at 28 Sep 2026, 07:00 IST."))
    }

    @Test
    fun `coordinates use a dot decimal separator whatever the locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertTrue(SosMessageBuilder.mapsLink(SosLocation(22.5, 88.3)).endsWith("22.500000,88.300000"))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }
}
