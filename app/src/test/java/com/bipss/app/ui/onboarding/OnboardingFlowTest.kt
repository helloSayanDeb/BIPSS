package com.bipss.app.ui.onboarding

import com.bipss.app.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingFlowTest {

    @Test
    fun `a new install walks the seven steps in the specified order`() {
        assertEquals(
            listOf(
                OnboardingStep.NOTIFICATIONS, OnboardingStep.PROFILE, OnboardingStep.BAND, OnboardingStep.MODEL,
                OnboardingStep.LOCATION, OnboardingStep.SMS, OnboardingStep.CONTACTS
            ),
            OnboardingStep.sequence(catchUp = false)
        )
        assertEquals(OnboardingStep.TOTAL, OnboardingStep.sequence(catchUp = false).size)
    }

    @Test
    fun `the step counter is 0-based in the full flow and absent in catch-up`() {
        assertEquals(0, OnboardingStep.indexOf(OnboardingStep.NOTIFICATIONS, catchUp = false))
        assertEquals(6, OnboardingStep.indexOf(OnboardingStep.CONTACTS, catchUp = false))
        assertNull(OnboardingStep.indexOf(OnboardingStep.LOCATION, catchUp = true))
        // Gender is only ever shown in catch-up; it has no place in the numbered flow.
        assertNull(OnboardingStep.indexOf(OnboardingStep.GENDER, catchUp = false))
    }

    @Test
    fun `catch-up asks only what the earlier onboarding never did`() {
        assertEquals(
            listOf(OnboardingStep.GENDER, OnboardingStep.LOCATION, OnboardingStep.SMS, OnboardingStep.CONTACTS),
            OnboardingStep.sequence(catchUp = true)
        )
    }

    @Test
    fun `shared steps carry the catch-up flag in their route`() {
        assertEquals(Routes.ONBOARDING_SMS, OnboardingStep.SMS.routeFor(catchUp = false))
        assertEquals("${Routes.ONBOARDING_SMS}?catchUp=true", OnboardingStep.SMS.routeFor(catchUp = true))
    }
}
