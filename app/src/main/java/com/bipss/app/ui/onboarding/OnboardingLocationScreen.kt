package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

/**
 * Step 5: location. Declining moves straight on, and nothing location-based
 * is fetched until the user grants it later (the environment sync makes no
 * request without it). Granting runs a real calibration and a real fetch,
 * each source reported as it came back.
 */
@Composable
fun OnboardingLocationScreen(
    navController: NavController,
    catchUp: Boolean,
    /** Opened after a later grant (see AppShell): no step counter, and finishing goes back. */
    standalone: Boolean = false,
    vm: OnboardingLocationViewModel = koinViewModel()
) {
    val colors = BipssTheme.colors
    val phase by vm.phase.collectAsStateWithLifecycle()
    val next: () -> Unit = {
        if (standalone) navController.popBackStack()
        else navController.goToNextOnboardingStep(OnboardingStep.LOCATION, catchUp)
    }
    val continueLabel = if (standalone) AppText.text(R.string.copy_11a6767d5674, "Done") else AppText.text(R.string.copy_31fbef162594, "Continue")
    val permission = rememberLocationPermission()

    // Runs setup once location is allowed: from the dialog, from Android's
    // settings (re-read on resume), or already (an existing user in catch-up).
    LaunchedEffect(permission.isGranted) { if (permission.isGranted) vm.startIfIdle() }

    val setupRunning = phase is OnboardingLocationViewModel.Phase.Calibrating ||
        phase is OnboardingLocationViewModel.Phase.Fetching

    OnboardingScaffold(
        step = if (standalone) null else OnboardingStep.indexOf(OnboardingStep.LOCATION, catchUp),
        totalSteps = OnboardingStep.TOTAL,
        title = if (permission.isGranted) AppText.text(R.string.copy_d0287803f2cb, "Setting up your local data") else AppText.text(R.string.copy_036b2d390f89, "Your location"),
        subtitle = if (permission.isGranted) null
                   else AppText.text(R.string.copy_0ef73640b2d3, "See local conditions and share your location in an SOS. You can skip this."),
        onBack = if (setupRunning) null else ({ navController.popBackStack() }),
        footer = {
            val p = phase
            when {
                !permission.isGranted -> {
                    PrimaryButton(AppText.text(R.string.copy_843d87d29be6, "Allow location"), onClick = { permission.request() })
                    Spacer(Modifier.height(Space.s))
                    GhostButton(AppText.text(R.string.copy_a0e63d7c7125, "Not now"), onClick = next, modifier = Modifier.fillMaxWidth())
                }
                p is OnboardingLocationViewModel.Phase.Done && p.anyFailed -> {
                    PrimaryButton(AppText.text(R.string.copy_d8b8392e2c54, "Try again"), onClick = { vm.start() })
                    Spacer(Modifier.height(Space.s))
                    GhostButton(if (standalone) AppText.text(R.string.copy_11a6767d5674, "Done") else AppText.text(R.string.copy_873567be309f, "Continue anyway"), onClick = next, modifier = Modifier.fillMaxWidth())
                }
                p is OnboardingLocationViewModel.Phase.Done -> PrimaryButton(continueLabel, onClick = next)
                else -> PrimaryButton(AppText.text(R.string.copy_c894bba9c3e7, "Setting up…"), onClick = {}, enabled = false, loading = true)
            }
        }
    ) {
        if (!permission.isGranted) {
            FeatureRow(Icons.Rounded.LocationOn, colors.air, AppText.text(R.string.copy_5930a6113c3e, "Know what's around you"),
                AppText.text(R.string.copy_cfd8d05f7c7f, "Weather, air quality and nearby safety alerts."))
            Spacer(Modifier.height(Space.l))
            FeatureRow(Icons.Rounded.Sms, colors.bandRisk, AppText.text(R.string.copy_28c0e8f3cb4c, "Help contacts find you"),
                AppText.text(R.string.copy_4158c0d53abe, "Include a map link when you send an SOS."))
            Spacer(Modifier.height(Space.l))
            Text(AppText.text(R.string.copy_be08929abdef, "Your location is shared with weather and nearby-place services to provide these features. Precise location improves local results."),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            if (permission.isPermanentlyDenied) {
                Spacer(Modifier.height(Space.l))
                InfoBanner(
                    text = AppText.text(R.string.copy_98be3fe9afca, "Location was turned off for BIPSS. You can allow it in Android's settings."),
                    icon = Icons.Rounded.Info,
                    tint = colors.bandMonitor,
                    action = {
                        GhostButton(AppText.text(R.string.copy_74a883a037bc, "Settings"), onClick = { com.bipss.app.sos.openAppSettings(navController.context) },
                                    color = colors.bandMonitor)
                    }
                )
            }
            return@OnboardingScaffold
        }

        when (val p = phase) {
            OnboardingLocationViewModel.Phase.Idle ->
                Progress(AppText.text(R.string.copy_bbe5fc3b9ef3, "Starting…"))
            is OnboardingLocationViewModel.Phase.Calibrating ->
                Progress(
                    AppText.text(R.string.copy_f286794cc0ac, "Finding your position") + (p.accuracyM?.let { AppText.text(R.string.copy_2019300cf6e7, " · accurate to ±%1\$s m", it) } ?: "…"),
                    note = if (p.approximate) AppText.text(R.string.copy_f34a4185f31c, "Approximate location only, so a rough fix is expected.") else null
                )
            OnboardingLocationViewModel.Phase.Fetching ->
                Progress(AppText.text(R.string.copy_3c4597b509e0, "Checking weather, air quality and alerts for your area…"))
            is OnboardingLocationViewModel.Phase.Done -> {
                BipssCard {
                    p.sources.forEachIndexed { i, s ->
                        if (i > 0) HairlineDivider()
                        SourceRow(s)
                    }
                }
                if (p.approximate) {
                    Spacer(Modifier.height(Space.l))
                    InfoBanner(
                        text = AppText.text(R.string.copy_b395807bf183, "BIPSS has approximate location only. SOS texts will say the location is approximate."),
                        icon = Icons.Rounded.LocationOn,
                        tint = colors.bandMonitor
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(text: String, note: String? = null) {
    val colors = BipssTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.textSecondary)
        Spacer(Modifier.width(Space.m))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
    }
    if (note != null) {
        Spacer(Modifier.height(Space.s))
        Text(note, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

@Composable
private fun SourceRow(s: OnboardingLocationViewModel.SourceResult) {
    val colors = BipssTheme.colors
    val (icon, tint) = when (s.status) {
        OnboardingLocationViewModel.SourceStatus.OK                 -> Icons.Rounded.CheckCircle to colors.bandNormal
        OnboardingLocationViewModel.SourceStatus.NOT_AVAILABLE_HERE -> Icons.Rounded.RemoveCircleOutline to colors.textTertiary
        OnboardingLocationViewModel.SourceStatus.FAILED             -> Icons.Rounded.Cancel to colors.bandRisk
    }
    Row(Modifier.padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = s.status.name, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Column {
            Text(s.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Spacer(Modifier.height(Space.xs))
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}
