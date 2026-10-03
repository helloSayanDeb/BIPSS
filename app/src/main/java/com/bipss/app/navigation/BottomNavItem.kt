package com.bipss.app.navigation

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.ui.graphics.vector.ImageVector

data class BottomNavItem(
    val route:       String,
    val label:       String,
    val icon:        ImageVector,
    val contentDesc: String
)

val bottomNavItems get() = listOf(
    BottomNavItem(Routes.DASHBOARD_TODAY,    AppText.text(R.string.copy_3a78695388b3, "Home"),     Icons.Outlined.Home,           AppText.text(R.string.copy_c3e72440bdd4, "Home tab")),
    BottomNavItem(Routes.DASHBOARD_INSIGHTS, AppText.text(R.string.copy_2a932f90172e, "Insights"),   Icons.Outlined.Insights, AppText.text(R.string.copy_ac32c766b808, "Insights tab")),
    BottomNavItem(Routes.DASHBOARD_CHAT,     AppText.text(R.string.copy_e6b7456c0995, "Coach"),    Icons.AutoMirrored.Outlined.Chat,           AppText.text(R.string.copy_c33de74455d6, "Coach tab")),
    BottomNavItem(Routes.DASHBOARD_PROFILE,  AppText.text(R.string.copy_d47d7cb0e4f8, "More"),     Icons.Outlined.Menu,           AppText.text(R.string.copy_1be0eaaff3b6, "More tab")),
)

/** Routes where the bottom nav should be shown */
val dashboardRoutes = bottomNavItems.map { it.route }.toSet()
