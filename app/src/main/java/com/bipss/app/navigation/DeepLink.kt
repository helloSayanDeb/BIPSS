package com.bipss.app.navigation

import android.content.Intent

/**
 * Notification → screen routing, shared by the producer (BipssNotifications)
 * and the consumer (MainActivity), so the two can never drift apart again.
 *
 * Before this, notifications put a "navigate_to" extra on the intent and
 * nothing anywhere read it: MainActivity had no intent handling and no
 * onNewIntent, so every tap simply opened (or resumed) the app wherever it was.
 */
object DeepLink {
    const val EXTRA_NAVIGATE_TO = "navigate_to"

    enum class Target(val key: String, val route: String) {
        TODAY("today", Routes.DASHBOARD_TODAY),
        INSIGHTS("insights", Routes.DASHBOARD_INSIGHTS),
        COACH("coach", Routes.DASHBOARD_CHAT),
        SLEEP("sleep", Routes.SLEEP_DETAIL),
        SLEEP_PLAN("sleep_plan", Routes.SLEEP_PLANNER),
        WEEKLY("weekly", Routes.WEEKLY_REPORT);

        /** Tab destinations use the bottom bar's navigate options; the rest are pushed on top of Home. */
        val isTab: Boolean get() = this == TODAY || this == INSIGHTS || this == COACH
    }

    /**
     * The target of a notification tap, or null. An intent re-delivered from
     * Recents (API 26-30 relaunch the task's original intent after back-out)
     * is a relaunch, not a new tap, and is ignored.
     */
    fun fromIntent(intent: Intent?): Target? {
        if (intent == null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        val key = intent.getStringExtra(EXTRA_NAVIGATE_TO) ?: return null
        return Target.entries.firstOrNull { it.key == key }
    }
}
