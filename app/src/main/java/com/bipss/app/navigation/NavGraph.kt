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

            // ── Settings ──────────────────────────────────────────────────────────

            composable(Routes.SETTINGS) {
                SettingsScreen(navController = navController)
            }

            composable(Routes.SETTINGS_CONTACT_PICKER) {
                com.bipss.app.ui.settings.ContactPickerScreen(navController = navController)
            }
        }
    }
}
