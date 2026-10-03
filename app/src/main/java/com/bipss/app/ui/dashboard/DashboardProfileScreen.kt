package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.navigation.*
import com.bipss.app.ui.activity.ActivityViewModel
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.*
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardProfileScreen(navController: NavController, vm: DashboardViewModel = koinViewModel(),
    activityVm: ActivityViewModel = koinViewModel()) {
    val daily by activityVm.ui.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
        .padding(LocalScaffoldPadding.current).padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Spacer(Modifier.height(Space.xs))
        Text(AppText.text(R.string.copy_d47d7cb0e4f8, "More"), style = MaterialTheme.typography.headlineLarge, color = colors.textPrimary)
        BipssCard(onClick = { navController.navigate(Routes.ONBOARDING_PROFILE_EDIT) }) {
            Text(daily.profile?.name?.takeIf { it.isNotBlank() } ?: AppText.text(R.string.copy_528d89ad72cb, "Your profile"), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            Spacer(Modifier.height(Space.s))
            Text(AppText.text(R.string.copy_ef55f5a7f733, "Personal details & measurements  →"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        SectionHeader(AppText.text(R.string.copy_e474894a4bf7, "Your wellbeing"))
        BipssCard {
            MoreLink(AppText.text(R.string.copy_bbb419f0149d, "Activity & steps"), AppText.text(R.string.copy_b1ff4d0f3b7d, "Goals, history and your activity log"), Icons.Rounded.DirectionsRun) { navController.navigate(Routes.ACTIVITY_DETAIL) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_7826b2bdbdc1, "Body measurements"), AppText.text(R.string.copy_9220e22034f3, "Height, weight and BMI"), Icons.Rounded.AccessibilityNew) { navController.navigate(Routes.BODY_DETAIL) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_1d9605ff4a17, "Hydration"), AppText.text(R.string.copy_8915a96388f6, "Your goal and daily drinks"), Icons.Rounded.WaterDrop) { navController.navigate(Routes.HYDRATION_DETAIL) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_d466bcf52eb6, "Sleep"), AppText.text(R.string.copy_48e91fc0a926, "Your nights and sleep patterns"), Icons.Rounded.Bedtime) { navController.navigate(Routes.SLEEP_DETAIL) }
            if (daily.profile?.gender == com.bipss.app.data.Gender.FEMALE) {
                HairlineDivider()
                MoreLink(AppText.text(R.string.copy_518653530449, "Cycle"), AppText.text(R.string.copy_e022a3ac2600, "Calendar and notes"), Icons.Rounded.CalendarMonth) { navController.navigate(Routes.CYCLE_DETAIL) }
            }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_068863289325, "Your trends"), AppText.text(R.string.copy_63ca6dc4ad05, "Sleep, recovery and activity over time"), Icons.Rounded.Insights) { navController.navigate(Routes.TRENDS) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_4b2783146693, "Sleep planner"), AppText.text(R.string.copy_250a24300cb8, "Bedtime, wake time and sleep debt"), Icons.Rounded.Bedtime) { navController.navigate(Routes.SLEEP_PLANNER) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_be80ef360690, "Food journal"), if (daily.data.nutritionEnabled) AppText.text(R.string.copy_6e2d0086d1a7, "Meals and nutrition you log") else AppText.text(R.string.copy_50588c9edbcd, "Optional · set up when you're ready"), Icons.Rounded.Restaurant) { navController.navigate(Routes.NUTRITION) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_4eac4c1136cd, "Breathing break"), AppText.text(R.string.copy_22a9fe6f17a7, "One quiet minute"), Icons.Rounded.Air) { navController.navigate(Routes.BREATHING) }
        }
        SectionHeader(AppText.text(R.string.copy_9c3728a79757, "Your app"))
        BipssCard {
            MoreLink(AppText.text(R.string.copy_b3ad0f6efcb9, "Your band"), AppText.text(R.string.copy_1b5ab66e5a9b, "Connect or manage your device"), Icons.Rounded.Watch) { navController.navigate(Routes.ONBOARDING_CONNECT_BAND_REPAIR) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_9463d00b353e, "Emergency contacts"), AppText.text(R.string.copy_777d43067c40, "Get ready for an SOS"), Icons.Rounded.LocalHospital) { navController.navigate(Routes.SETTINGS_EMERGENCY) }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_e0c88e602685, "Coach download"), AppText.text(R.string.copy_a107aad19cf9, "Add optional AI conversations"), Icons.Rounded.AutoAwesome) { navController.navigate(Routes.ONBOARDING_DOWNLOAD_MODEL + "?standalone=true") }
            HairlineDivider()
            MoreLink(AppText.text(R.string.copy_f6d3343e3f5a, "Settings & privacy"), AppText.text(R.string.copy_a191cb921b9f, "Appearance, backup and data controls"), Icons.Rounded.Settings) { navController.navigate(Routes.SETTINGS) }
        }
        Spacer(Modifier.height(Space.xl))
    }
}

@Composable private fun MoreLink(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = BipssTheme.colors.textPrimary)
                Spacer(Modifier.height(Space.s))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = BipssTheme.colors.textTertiary)
        }
    }
}
