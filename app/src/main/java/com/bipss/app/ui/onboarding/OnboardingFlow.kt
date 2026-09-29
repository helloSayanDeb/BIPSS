package com.bipss.app.ui.onboarding

import androidx.navigation.NavController
import com.bipss.app.navigation.Routes

/**
 * The order of the onboarding steps, in one place.
 *
 * A new install goes Welcome, then the seven numbered steps. A user who
 * finished the earlier, shorter onboarding is shown only what it never asked:
 * gender, location, SMS and emergency contacts ("catch-up"), without a step
 * counter.
 */
enum class OnboardingStep(val route: String) {
    NOTIFICATIONS(Routes.ONBOARDING_NOTIFICATIONS),
    PROFILE(Routes.ONBOARDING_PROFILE),
    BAND(Routes.ONBOARDING_CONNECT_BAND),
    MODEL(Routes.ONBOARDING_DOWNLOAD_MODEL),
    LOCATION(Routes.ONBOARDING_LOCATION),
    SMS(Routes.ONBOARDING_SMS),
    CONTACTS(Routes.ONBOARDING_CONTACTS),
    /** Catch-up only: the gender question existing profiles never answered. */
    GENDER(Routes.ONBOARDING_GENDER);

    companion object {
        const val TOTAL = 7

        private val FULL = listOf(NOTIFICATIONS, PROFILE, BAND, MODEL, LOCATION, SMS, CONTACTS)
        private val CATCH_UP = listOf(GENDER, LOCATION, SMS, CONTACTS)

        fun sequence(catchUp: Boolean) = if (catchUp) CATCH_UP else FULL

        /** 0-based position for the step counter, or null in catch-up (no counter). */
        fun indexOf(step: OnboardingStep, catchUp: Boolean): Int? =
            if (catchUp) null else FULL.indexOf(step).takeIf { it >= 0 }
    }
}

/** The route for [step], carrying the catch-up flag to the screens that are shared. */
fun OnboardingStep.routeFor(catchUp: Boolean): String = if (catchUp) "$route?catchUp=true" else route

/**
 * Moves to the step after [current], or returns false when [current] is the
 * last one (the caller then finishes onboarding).
 */
fun NavController.goToNextOnboardingStep(current: OnboardingStep, catchUp: Boolean): Boolean {
    val seq = OnboardingStep.sequence(catchUp)
    val next = seq.getOrNull(seq.indexOf(current) + 1) ?: return false
    navigate(next.routeFor(catchUp))
    return true
}

/** Leaves onboarding for the tab shell, clearing every onboarding screen from the back stack. */
fun NavController.finishOnboarding() {
    navigate(Routes.DASHBOARD_TODAY) {
        popUpTo(graph.id) { inclusive = true }
    }
}
