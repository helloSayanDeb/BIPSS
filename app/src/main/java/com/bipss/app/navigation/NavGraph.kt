package com.bipss.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bipss.app.ui.dashboard.*
import com.bipss.app.ui.onboarding.*
import com.bipss.app.ui.settings.SettingsScreen

private fun catchUpArg() = navArgument("catchUp") {
    type         = NavType.BoolType
    defaultValue = false
}

private fun androidx.navigation.NavBackStackEntry.catchUp(): Boolean =
    arguments?.getBoolean("catchUp") ?: false

/** Provides scaffold innerPadding to all screens without threading it through every composable */
val LocalScaffoldPadding = staticCompositionLocalOf { PaddingValues() }

@Composable
fun NavGraph(
    navController:    NavHostController,
    startDestination: String,
    innerPadding:     PaddingValues = PaddingValues()
) {
    CompositionLocalProvider(LocalScaffoldPadding provides innerPadding) {
        NavHost(
            navController    = navController,
            startDestination = startDestination
        ) {

            // ── Onboarding ────────────────────────────────────────────────────────

            composable(Routes.ONBOARDING_WELCOME) {
                OnboardingWelcomeScreen(navController = navController)
            }

            composable(
                route     = "onboarding_profile?editMode={editMode}",
                arguments = listOf(navArgument("editMode") {
                    type         = NavType.BoolType
                    defaultValue = false
                })
            ) { backStackEntry ->
                val editMode = backStackEntry.arguments?.getBoolean("editMode") ?: false
                OnboardingProfileScreen(navController = navController, isEditMode = editMode)
            }

            composable(
                route     = "onboarding_connect_band?rePairMode={rePairMode}",
                arguments = listOf(navArgument("rePairMode") {
                    type         = NavType.BoolType
                    defaultValue = false
                })
            ) { backStackEntry ->
                val rePairMode = backStackEntry.arguments?.getBoolean("rePairMode") ?: false
                OnboardingConnectBandScreen(navController = navController, isRePairMode = rePairMode)
            }

            composable(Routes.ONBOARDING_DOWNLOAD_MODEL) {
                OnboardingDownloadModelScreen(navController = navController)
            }

            composable(Routes.ONBOARDING_NOTIFICATIONS) {
                OnboardingNotificationsScreen(navController = navController)
            }

            composable(Routes.LOCATION_SETUP) {
                OnboardingLocationScreen(navController = navController, catchUp = false, standalone = true)
            }

            composable(Routes.ONBOARDING_GENDER) {
                OnboardingGenderScreen(navController = navController)
            }

            // Location, SMS and contacts are shared with the catch-up flow,
            // which passes catchUp=true (no step counter; different next step).
            composable(
                route     = "${Routes.ONBOARDING_LOCATION}?catchUp={catchUp}",
                arguments = listOf(catchUpArg())
            ) { entry ->
                OnboardingLocationScreen(navController = navController, catchUp = entry.catchUp())
            }

            composable(
                route     = "${Routes.ONBOARDING_SMS}?catchUp={catchUp}",
                arguments = listOf(catchUpArg())
            ) { entry ->
                OnboardingSmsScreen(navController = navController, catchUp = entry.catchUp())
            }

            composable(
                route     = "${Routes.ONBOARDING_CONTACTS}?catchUp={catchUp}",
                arguments = listOf(catchUpArg())
            ) { entry ->
                OnboardingContactsScreen(navController = navController, catchUp = entry.catchUp())
            }

            // ── Dashboard ─────────────────────────────────────────────────────────

            composable(Routes.DASHBOARD_TODAY) {
                DashboardTodayScreen(navController = navController)
            }

            composable(Routes.DASHBOARD_INSIGHTS) {
                DashboardInsightsScreen(navController = navController)
            }

            composable(Routes.DASHBOARD_CHAT) {
                DashboardChatScreen(navController = navController)
            }

            composable(Routes.DASHBOARD_PROFILE) {
                DashboardProfileScreen(navController = navController)
            }

            composable(Routes.SLEEP_DETAIL) {
                com.bipss.app.ui.sleep.SleepDetailScreen(navController = navController)
            }

            composable(Routes.RECOVERY_DETAIL) {
                com.bipss.app.ui.recovery.RecoveryDetailScreen(navController = navController)
            }

            composable(Routes.STRAIN_DETAIL) {
                com.bipss.app.ui.strain.StrainDetailScreen(navController = navController)
            }

            // ── Metric history ────────────────────────────────────────────────────

            composable(
                route     = Routes.METRIC_DETAIL_PATTERN,
                arguments = listOf(navArgument("metricType") { type = NavType.StringType })
            ) { backStackEntry ->
                com.bipss.app.ui.metric.MetricDetailScreen(
                    metricType    = backStackEntry.arguments?.getString("metricType")
                        ?: com.bipss.app.data.MetricTypes.HR,
                    navController = navController
                )
            }

            // ── Settings ──────────────────────────────────────────────────────────

            composable(
                route     = "${Routes.SETTINGS}?section={section}",
                arguments = listOf(navArgument("section") {
                    type     = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { entry ->
                SettingsScreen(
                    navController = navController,
                    openAtEmergency = entry.arguments?.getString("section") == "emergency"
                )
            }

            composable(Routes.SETTINGS_CONTACT_PICKER) {
                com.bipss.app.ui.settings.ContactPickerScreen(navController = navController)
            }
        }
    }
}
