package com.bipss.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.bipss.app.ui.components.FeatureRow
import com.bipss.app.ui.components.GhostButton
import com.bipss.app.ui.components.OnboardingScaffold
import com.bipss.app.ui.components.PrimaryButton
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

/**
 * Step 1: notifications. Asked here, with the reasons, instead of by a bare
 * system dialog the moment the app first opened.
 */
@Composable
fun OnboardingNotificationsScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = BipssTheme.colors
    val next: () -> Unit = { navController.goToNextOnboardingStep(OnboardingStep.NOTIFICATIONS, catchUp = false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    // Before Android 13 notifications need no runtime permission.
    val needsAsk = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

    OnboardingScaffold(
        step = OnboardingStep.indexOf(OnboardingStep.NOTIFICATIONS, catchUp = false),
        totalSteps = OnboardingStep.TOTAL,
        title = "Notifications",
        subtitle = "What BIPSS uses notifications for.",
        onBack = { navController.popBackStack() },
        footer = {
            PrimaryButton(
                text = if (needsAsk) "Allow notifications" else "Continue",
                onClick = { if (needsAsk) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else next() }
            )
            if (needsAsk) {
                Spacer(Modifier.height(Space.s))
                GhostButton("Not now", onClick = next, modifier = Modifier.fillMaxWidth())
            }
        }
    ) {
        FeatureRow(
            icon = Icons.Rounded.Warning, tint = colors.bandRisk,
            title = "Official weather warnings",
            body = "Serious IMD and NDMA warnings for where you are."
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.Bedtime, tint = colors.sleep,
            title = "Your morning summary",
            body = "Once your band has recorded a night."
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.WaterDrop, tint = colors.spo2,
            title = "Drink reminders",
            body = "Only after you have started logging water."
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.Watch, tint = colors.textSecondary,
            title = "While your band is connected",
            body = "Android requires an ongoing notification so the connection can keep running overnight."
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.NotificationsActive, tint = colors.textSecondary,
            title = "You can change this any time",
            body = "In Android's settings for BIPSS."
        )
    }
}
