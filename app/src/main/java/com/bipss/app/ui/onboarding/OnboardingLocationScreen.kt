package com.bipss.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
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
    val continueLabel = if (standalone) "Done" else "Continue"
    val permission = rememberLocationPermission()

    // Runs setup once location is allowed: from the dialog, from Android's
    // settings (re-read on resume), or already (an existing user in catch-up).
    LaunchedEffect(permission.isGranted) { if (permission.isGranted) vm.startIfIdle() }

    val setupRunning = phase is OnboardingLocationViewModel.Phase.Calibrating ||
        phase is OnboardingLocationViewModel.Phase.Fetching

    OnboardingScaffold(
        step = if (standalone) null else OnboardingStep.indexOf(OnboardingStep.LOCATION, catchUp),
        totalSteps = OnboardingStep.TOTAL,
        title = if (permission.isGranted) "Setting up your local data" else "Your location",
        subtitle = if (permission.isGranted) null
                   else "Optional. Everything else in BIPSS works without it.",
        onBack = if (setupRunning) null else ({ navController.popBackStack() }),
        footer = {
            val p = phase
            when {
                !permission.isGranted -> {
                    PrimaryButton("Allow location", onClick = { permission.request() })
                    Spacer(Modifier.height(Space.s))
                    GhostButton("Not now", onClick = next, modifier = Modifier.fillMaxWidth())
                }
                p is OnboardingLocationViewModel.Phase.Done && p.anyFailed -> {
                    PrimaryButton("Try again", onClick = { vm.start() })
                    Spacer(Modifier.height(Space.s))
                    GhostButton(if (standalone) "Done" else "Continue anyway", onClick = next, modifier = Modifier.fillMaxWidth())
                }
                p is OnboardingLocationViewModel.Phase.Done -> PrimaryButton(continueLabel, onClick = next)
                else -> PrimaryButton("Setting up…", onClick = {}, enabled = false, loading = true)
            }
        }
    ) {
        if (!permission.isGranted) {
            FeatureRow(
                icon = Icons.Rounded.Sms, tint = colors.bandRisk,
                title = "In an SOS text",
                body = "Your emergency contacts get a map link to where you are."
            )
            Spacer(Modifier.height(Space.l))
            FeatureRow(
                icon = Icons.Rounded.Thermostat, tint = colors.temp,
                title = "Local weather, air quality and alerts",
                body = "For heat stress and air-quality scores, and official warnings that apply to you."
            )
            Spacer(Modifier.height(Space.xl))
            Text(
                "For weather, your coordinates are sent to Open-Meteo; for air quality, your state is sent " +
                    "to the government's open-data portal; Android's geocoder looks up your district. BIPSS " +
                    "keeps your last position on this phone, and records where you were in any SOS it sends.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
            if (permission.isPermanentlyDenied) {
                Spacer(Modifier.height(Space.l))
                InfoBanner(
                    text = "Location was turned off for BIPSS. You can allow it in Android's settings.",
                    icon = Icons.Rounded.Info,
                    tint = colors.bandMonitor,
                    action = {
                        GhostButton("Settings", onClick = { com.bipss.app.sos.openAppSettings(navController.context) },
                                    color = colors.bandMonitor)
                    }
                )
            }
            return@OnboardingScaffold
        }

        when (val p = phase) {
            OnboardingLocationViewModel.Phase.Idle ->
                Progress("Starting…")
            is OnboardingLocationViewModel.Phase.Calibrating ->
                Progress(
                    "Finding your position" + (p.accuracyM?.let { " · accurate to ±$it m" } ?: "…"),
                    note = if (p.approximate) "Approximate location only, so a rough fix is expected." else null
                )
            OnboardingLocationViewModel.Phase.Fetching ->
                Progress("Checking weather, air quality and alerts for your area…")
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
                        text = "BIPSS has approximate location only. SOS texts will say the location is approximate.",
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
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}
