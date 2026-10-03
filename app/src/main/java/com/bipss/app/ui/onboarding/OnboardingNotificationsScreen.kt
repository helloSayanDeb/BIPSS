package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
        title = AppText.text(R.string.copy_788011833a5a, "Notifications"),
        subtitle = AppText.text(R.string.copy_3999f4a21f88, "What BIPSS uses notifications for."),
        onBack = { navController.popBackStack() },
        footer = {
            PrimaryButton(
                text = if (needsAsk) AppText.text(R.string.copy_001559c8913c, "Allow notifications") else AppText.text(R.string.copy_31fbef162594, "Continue"),
                onClick = { if (needsAsk) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else next() }
            )
            if (needsAsk) {
                Spacer(Modifier.height(Space.s))
                GhostButton(AppText.text(R.string.copy_a0e63d7c7125, "Not now"), onClick = next, modifier = Modifier.fillMaxWidth())
            }
        }
    ) {
        FeatureRow(
            icon = Icons.Rounded.Warning, tint = colors.bandRisk,
            title = AppText.text(R.string.copy_073c4d0a3a68, "Official weather warnings"),
            body = AppText.text(R.string.copy_af6ef5935e5c, "Important weather and safety alerts near you.")
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.Bedtime, tint = colors.sleep,
            title = AppText.text(R.string.copy_d77fb02c95cb, "Your morning summary"),
            body = AppText.text(R.string.copy_18d450a1cb78, "Once your band has recorded a night.")
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.WaterDrop, tint = colors.spo2,
            title = AppText.text(R.string.copy_dc028df4fb34, "Drink reminders"),
            body = AppText.text(R.string.copy_d40ed41297d5, "Only after you have started logging water.")
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.Watch, tint = colors.textSecondary,
            title = AppText.text(R.string.copy_20d711b0b08b, "While your band is connected"),
            body = AppText.text(R.string.copy_ce36d9f60587, "Android requires an ongoing notification so the connection can keep running overnight.")
        )
        Spacer(Modifier.height(Space.l))
        FeatureRow(
            icon = Icons.Rounded.NotificationsActive, tint = colors.textSecondary,
            title = AppText.text(R.string.copy_9acc515d107d, "You can change this any time"),
            body = AppText.text(R.string.copy_e9a526f77a11, "In Android's settings for BIPSS.")
        )
    }
}
