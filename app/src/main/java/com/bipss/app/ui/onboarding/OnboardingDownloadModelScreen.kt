package com.bipss.app.ui.onboarding

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
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

/**
 * Step 4: the optional on-device model. In a build without a model to offer
 * it says so and moves on; otherwise nothing is fetched until the user taps.
 */
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

    val next: () -> Unit = { navController.goToNextOnboardingStep(OnboardingStep.MODEL, catchUp = false) }

    OnboardingScaffold(
        step = OnboardingStep.indexOf(OnboardingStep.MODEL, catchUp = false),
        totalSteps = OnboardingStep.TOTAL,
        title = "On-device AI model",
        subtitle = if (state.configured)
            "Optional. A language model that runs entirely on your phone, so your questions aren't sent to a server."
        else
            "Optional, and not available yet.",
        onBack = { navController.popBackStack() },
        footer = {
            when {
                !state.configured || isDone -> PrimaryButton("Continue", onClick = next)
                isBusy -> {
                    PrimaryButton("Downloading…", onClick = {}, loading = true)
                    Spacer(Modifier.height(Space.s))
                    GhostButton("Stop and continue", onClick = { vm.stopDownload(); next() },
                                modifier = Modifier.fillMaxWidth())
                }
                state.manifestSizeMb != null -> {
                    PrimaryButton("Download  ·  ${state.manifestSizeMb} MB", onClick = vm::startDownload)
                    Spacer(Modifier.height(Space.s))
                    GhostButton("Not now", onClick = next, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    PrimaryButton(
                        text = if (state.manifestCheckFailed) "Try again" else "Check download size",
                        onClick = vm::checkSize,
                        loading = state.checking
                    )
                    Spacer(Modifier.height(Space.s))
                    GhostButton("Not now", onClick = next, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    ) {
        if (!state.configured) {
            InfoBanner(
                text = "No on-device model is available for this version of BIPSS yet. The Coach tab uses " +
                    "rule-based replies instead, and everything else works fully without a model.",
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor,
            )
            return@OnboardingScaffold
        }

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
                body = "Connects heat, air quality and your band's readings into one explanation."
            )
            Spacer(Modifier.height(Space.xl))
            FeatureRow(
                icon = Icons.Rounded.TipsAndUpdates,
                tint = colors.bandMonitor,
                title = "Suggested next steps",
                body = "Turns the current picture into concrete things you can do."
            )
        }

        Spacer(Modifier.height(Space.l))
        InfoBanner(
            text = "Metrics, scores and alerts all work fully without it. You can add it any time from Settings.",
            icon = Icons.Rounded.Info,
            tint = colors.bandNormal,
        )

        if (state.manifestCheckFailed && !isBusy && !isDone) {
            Spacer(Modifier.height(Space.m))
            InfoBanner(
                text = "Couldn't reach the model server. Check your connection, or skip this for now.",
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor,
            )
        }

        if (isBusy || isDone || progress is DownloadProgress.Failure) {
            Spacer(Modifier.height(Space.xl))
            DownloadStatusCard(progress)
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
                    (if (progress.estimatedSecondsRemaining > 0) "About ${progress.estimatedSecondsRemaining}s remaining · " else "") +
                        "keep BIPSS open while it downloads",
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
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "The Coach tab now answers with the on-device model.",
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
                        Spacer(Modifier.height(Space.xs))
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
