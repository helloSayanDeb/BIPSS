package com.bipss.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import com.bipss.app.navigation.NavGraph
import com.bipss.app.navigation.Routes
import com.bipss.app.navigation.bottomNavItems
import com.bipss.app.navigation.dashboardRoutes
import com.bipss.app.ui.theme.BipssTheme

@Composable
fun AppShell(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.ONBOARDING_WELCOME
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute      = navBackStackEntry?.destination?.route

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
                        onItemClick  = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        }
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
    Column {
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                val tintColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else colors.textTertiary,
                    label = "tabTint"
                )
                val pillColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                    label = "tabPill"
                )

                Column(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onItemClick(item.route) }
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(pillColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.contentDesc,
                            tint = tintColor,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = tintColor
                    )
                }
            }
        }
    }
}
