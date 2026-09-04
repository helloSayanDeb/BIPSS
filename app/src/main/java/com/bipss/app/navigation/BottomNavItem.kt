package com.bipss.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.ui.graphics.vector.ImageVector

data class BottomNavItem(
    val route:       String,
    val label:       String,
    val icon:        ImageVector,
    val contentDesc: String
)

val bottomNavItems = listOf(
    BottomNavItem(Routes.DASHBOARD_TODAY,    "Home",     Icons.Outlined.Home,           "Home tab"),
    BottomNavItem(Routes.DASHBOARD_INSIGHTS, "Health",   Icons.Outlined.FavoriteBorder, "Health tab"),
    BottomNavItem(Routes.DASHBOARD_CHAT,     "Coach",    Icons.AutoMirrored.Outlined.Chat,           "Coach tab"),
    BottomNavItem(Routes.DASHBOARD_PROFILE,  "More",     Icons.Outlined.Menu,           "More tab"),
)

/** Routes where the bottom nav should be shown */
val dashboardRoutes = bottomNavItems.map { it.route }.toSet()
