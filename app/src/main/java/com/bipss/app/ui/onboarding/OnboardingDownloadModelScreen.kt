package com.bipss.app.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ai.DownloadProgress
import com.bipss.app.ai.MODEL_EXPECTED_SIZE_MB
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun OnboardingDownloadModelScreen(
    navController: NavController,
    vm: ModelDownloadViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val progress = state.progress

    val isDone = progress is DownloadProgress.Success || progress is DownloadProgress.AlreadyUpToDate
    val isBusy = progress is DownloadProgress.Initializing ||
                 progress is DownloadProgress.Downloading ||
                 progress is DownloadProgress.Verifying

    val finish: () -> Unit = {
        vm.completeOnboarding {
            navController.navigate(Routes.DASHBOARD_TODAY) {
                popUpTo(Routes.ONBOARDING_WELCOME) { inclusive = true }
            }
        }
    }

    OnboardingScaffold(
        step = 2,
        totalSteps = 3,
        title = "Add on-device intelligence",
        subtitle = "An optional ${MODEL_EXPECTED_SIZE_MB} MB language model that runs entirely on your phone — nothing is sent to a server.",
        onBack = { navController.popBackStack() },
        footer = {
            PrimaryButton(
                text = when {
                    isDone -> "Finish setup"
                    isBusy -> "Downloading…"
                    else   -> "Download  ·  $MODEL_EXPECTED_SIZE_MB MB"
                },
                loading = isBusy,
                onClick = { if (isDone) finish() else vm.startDownload() }
            )
            AnimatedVisibility(visible = !isDone) {
                Column {
                    Spacer(Modifier.height(Space.s))
                    GhostButton(
                        text = "Maybe later",
                        onClick = finish,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) {
        SectionLabel("What the model adds")
        Spacer(Modifier.height(Space.m))
        BipssCard {
            FeatureRow(
                icon = Icons.Rounded.Forum,
                tint = MaterialTheme.colorScheme.primary,
                title = "Conversational answers",
                body = "Ask why a score moved and get a reply grounded in your own readings."
            )
            Spacer(Modifier.height(Space.xl))
            FeatureRow(
                icon = Icons.Rounded.Insights,
                tint = colors.sleep,
                title = "Cross-domain narrative",
                body = "Connects heat, air quality and cardiac signals into one explanation."
            )
            Spacer(Modifier.height(Space.xl))
            FeatureRow(
                icon = Icons.Rounded.TipsAndUpdates,
                tint = colors.bandMonitor,
                title = "Personalised action plan",
                body = "Turns the current risk picture into concrete next steps."
            )
        }

        Spacer(Modifier.height(Space.l))
        InfoBanner(
            text = "Metrics, risk scores, alerts and offline forecasts all work fully without this. You can add it any time from Settings.",
            icon = Icons.Rounded.Info,
            tint = colors.bandNormal,
        )

        AnimatedVisibility(visible = isBusy || isDone || progress is DownloadProgress.Failure) {
            Column {
                Spacer(Modifier.height(Space.xl))
                DownloadStatusCard(progress)
            }
        }
    }
}

@Composable
private fun DownloadStatusCard(progress: DownloadProgress) {
    val colors = BipssTheme.colors

    BipssCard(
        accent = when (progress) {
            is DownloadProgress.Failure -> colors.bandRisk
            is DownloadProgress.Success, is DownloadProgress.AlreadyUpToDate -> colors.bandNormal
            else -> null
        }
    ) {
        when (progress) {
            is DownloadProgress.Downloading -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${(progress.fraction * 100).toInt()}",
                        style = MetricValue,
                        color = colors.textPrimary
                    )
                    Text(
                        "%",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${progress.mbDownloaded} / ${progress.totalMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Spacer(Modifier.height(Space.m))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = colors.raised,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(Space.s))
                Text(
                    "About ${progress.estimatedSecondsRemaining}s remaining · keep the app open",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
            }

            is DownloadProgress.Initializing -> IndeterminateRow("Checking for the latest model…")
            is DownloadProgress.Verifying -> IndeterminateRow("Verifying checksum…")

            is DownloadProgress.Success, is DownloadProgress.AlreadyUpToDate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.CheckCircle, colors.bandNormal)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Model ready",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            "Chat and AI insights are unlocked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            is DownloadProgress.Failure -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.AutoAwesome, colors.bandRisk)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Download failed",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            progress.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun IndeterminateRow(text: String) {
    val colors = BipssTheme.colors
    Column {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Space.m))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = colors.raised,
        )
    }
}
