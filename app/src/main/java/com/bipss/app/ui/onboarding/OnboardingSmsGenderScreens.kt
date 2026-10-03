package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.bipss.app.data.Gender
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.ui.components.FeatureRow
import com.bipss.app.ui.components.GenderSelector
import com.bipss.app.ui.components.GhostButton
import com.bipss.app.ui.components.InfoBanner
import com.bipss.app.ui.components.OnboardingScaffold
import com.bipss.app.ui.components.PrimaryButton
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Catch-up only: profiles made before the question existed have no gender.
 * It is asked on its own rather than sending the user back through the
 * whole profile form.
 */
@Composable
fun OnboardingGenderScreen(
    navController: NavController,
    prefs: UserPreferencesRepository = koinInject()
) {
    var selected by remember { mutableStateOf<Gender?>(null) }
    val scope = rememberCoroutineScope()
    // Answered before (catch-up interrupted and restarted): start from that answer.
    LaunchedEffect(Unit) { if (selected == null) selected = prefs.userProfile.first()?.gender }
    OnboardingScaffold(
        step = null,
        totalSteps = OnboardingStep.TOTAL,
        title = AppText.text(R.string.copy_325fe17087c7, "A few new things"),
        subtitle = AppText.text(R.string.copy_119bd896b6fb, "BIPSS has changed since you set it up. First: how do you describe yourself?"),
        footer = {
            PrimaryButton(
                text = AppText.text(R.string.copy_31fbef162594, "Continue"),
                enabled = selected != null,
                onClick = {
                    scope.launch {
                        selected?.let { prefs.setGender(it) }
                        navController.goToNextOnboardingStep(OnboardingStep.GENDER, catchUp = true)
                    }
                }
            )
        }
    ) {
        GenderSelector(selected = selected, onSelect = { selected = it })
        Spacer(Modifier.height(Space.l))
        GenderNote()
    }
}

/** What the answer is used for - and that it is used for nothing else. */
@Composable
fun GenderNote() {
    Text(
        AppText.text(R.string.copy_176108019b2f, "Helps personalize activity insights and cycle tracking. Choose the option that suits you."),
        style = MaterialTheme.typography.bodySmall,
        color = BipssTheme.colors.textSecondary
    )
}

/**
 * Step 6: SMS. SOS sends its text from this phone, so it needs permission to
 * send SMS - asked here, where the reason is on screen, rather than in the
 * middle of an emergency.
 */
@Composable
fun OnboardingSmsScreen(navController: NavController, catchUp: Boolean) {
    val context = LocalContext.current
    val colors = BipssTheme.colors
    val next: () -> Unit = { navController.goToNextOnboardingStep(OnboardingStep.SMS, catchUp) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    val canSendSms = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED

    OnboardingScaffold(
        step = OnboardingStep.indexOf(OnboardingStep.SMS, catchUp),
        totalSteps = OnboardingStep.TOTAL,
        title = AppText.text(R.string.copy_22923cbda0fa, "Emergency texts"),
        subtitle = AppText.text(R.string.copy_6587c5ce39f3, "When you trigger SOS, BIPSS texts your emergency contacts, and nearby hospitals that list ") +
            AppText.text(R.string.copy_c8c995e9db8e, "a mobile number, from this phone."),
        onBack = { navController.popBackStack() },
        footer = {
            when {
                !canSendSms || granted -> PrimaryButton(AppText.text(R.string.copy_31fbef162594, "Continue"), onClick = next)
                else -> {
                    PrimaryButton(AppText.text(R.string.copy_b4b197323259, "Allow SMS"), onClick = { launcher.launch(Manifest.permission.SEND_SMS) })
                    Spacer(Modifier.height(Space.s))
                    GhostButton(AppText.text(R.string.copy_a0e63d7c7125, "Not now"), onClick = next, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    ) {
        FeatureRow(
            icon = Icons.Rounded.Sms, tint = colors.bandRisk,
            title = AppText.text(R.string.copy_eb6370907fd3, "Only when you press SOS"),
            body = AppText.text(R.string.copy_295824db1e3f, "BIPSS sends no other texts. A 15-second countdown lets you cancel a mistaken tap. ") +
                AppText.text(R.string.copy_5c6d0f5a559e, "Your carrier's normal SMS charges apply.")
        )
        Spacer(Modifier.height(Space.xl))
        if (!canSendSms) {
            InfoBanner(
                text = AppText.text(R.string.copy_9c4192c5428d, "This device can't send SMS, so SOS can only offer to call 112."),
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor
            )
        } else if (granted) {
            InfoBanner(text = AppText.text(R.string.copy_4d677f631de2, "SMS is already allowed."), icon = Icons.Rounded.Info, tint = colors.bandNormal)
        }
    }
}
