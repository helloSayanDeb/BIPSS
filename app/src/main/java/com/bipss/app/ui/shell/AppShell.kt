package com.bipss.app.ui.shell

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.bipss.app.ui.components.glassSurface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.LaunchedEffect
import com.bipss.app.navigation.DeepLink
import com.bipss.app.navigation.NavGraph
import com.bipss.app.navigation.Routes
import com.bipss.app.navigation.bottomNavItems
import com.bipss.app.navigation.dashboardRoutes
import com.bipss.app.ui.theme.BipssTheme

@Composable
fun AppShell(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.ONBOARDING_WELCOME,
    deepLink: DeepLink.Target? = null,
    onDeepLinkConsumed: () -> Unit = {},
    locationSetupPending: Boolean = false,
    onLocationSetupConsumed: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute      = navBackStackEntry?.destination?.route

    // Notification taps. Dropped during onboarding - there is no tab shell to
    // land on, and replaying the tap once setup finishes would be a surprise -
    // and consumed once handled either way.
    LaunchedEffect(deepLink, startDestination) {
        val target = deepLink ?: return@LaunchedEffect
        if (startDestination != Routes.DASHBOARD_TODAY) {
            onDeepLinkConsumed()
            return@LaunchedEffect
        }
        runCatching {
            if (target.isTab) navController.navigateToTab(target.route)
            else {
                navController.navigateToTab(Routes.DASHBOARD_TODAY)
                navController.navigate(target.route) { launchSingleTop = true }
            }
        }
        onDeepLinkConsumed()
    }

    // Location granted since the app last looked (Android's settings, or the
    // Health tab): the same calibrate-fetch-verify screen as onboarding. During
    // onboarding its own location step does this, so the flag is just cleared.
    LaunchedEffect(locationSetupPending, startDestination) {
        if (!locationSetupPending) return@LaunchedEffect
        if (startDestination == Routes.DASHBOARD_TODAY) {
            runCatching { navController.navigate(Routes.LOCATION_SETUP) { launchSingleTop = true } }
        }
        onLocationSetupConsumed()
    }

    val isDashboard = dashboardRoutes.any {
        currentRoute?.startsWith(it) == true || currentRoute == it
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor   = BipssTheme.colors.textPrimary,
            bottomBar = {
                if (isDashboard) {
                    BipssTabBar(
                        items        = bottomNavItems,
                        currentRoute = currentRoute,
                        onItemClick  = { route -> navController.navigateToTab(route) }
                    )
                }
            }
        ) { innerPadding ->
            NavGraph(
                navController    = navController,
                startDestination = startDestination,
                innerPadding     = innerPadding
            )
        }
        // The tabs scroll edge to edge, so their content slid under the
        // transparent status bar and ran into the clock. The strip is the
        // status bar's own height (zero where a banner already covers it).
        if (isDashboard) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.background)
            )
        }
    }
}

/** Tab navigation with the same back-stack behaviour as tapping the bottom bar. */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState    = true
    }
}

/**
 * Bottom tab bar. Selected tab gets a tinted pill behind the icon plus the
 * brand colour — the icon-only colour change used before was too weak a
 * signal on a dark background.
 */
@Composable
private fun BipssTabBar(
    items: List<com.bipss.app.navigation.BottomNavItem>,
    currentRoute: String?,
    onItemClick: (String) -> Unit
) {
    val colors = BipssTheme.colors
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .glassSurface(radius = 36)
                .selectableGroup().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                val tint by animateColorAsState(if (selected) colors.textPrimary else colors.textSecondary, label = "tabTint")
                val fill by animateColorAsState(if (selected) colors.textPrimary.copy(alpha = .12f) else Color.Transparent, label = "tabPill")
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(28.dp)).background(fill)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onItemClick(item.route) })
                        .heightIn(min = 58.dp).padding(horizontal = 3.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(item.icon, contentDescription = item.contentDesc, tint = tint, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(item.label, color = tint, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium))
                }
            }
        }
    }
}
