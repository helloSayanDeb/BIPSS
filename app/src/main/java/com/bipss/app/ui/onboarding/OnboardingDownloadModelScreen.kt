package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
fun OnboardingDownloadModelScreen(navController: NavController, standalone: Boolean = false,
    vm: ModelDownloadViewModel = koinViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val progress = state.progress
    val done = progress is DownloadProgress.Success || progress is DownloadProgress.AlreadyUpToDate
    val busy = progress is DownloadProgress.Initializing || progress is DownloadProgress.Downloading ||
        progress is DownloadProgress.Verifying || progress is DownloadProgress.Preparing
    val next: () -> Unit = { if (standalone) navController.popBackStack()
        else navController.goToNextOnboardingStep(OnboardingStep.MODEL, catchUp = false) }
    OnboardingScaffold(
        step = if (standalone) null else OnboardingStep.indexOf(OnboardingStep.MODEL, catchUp = false),
        totalSteps = OnboardingStep.TOTAL, title = AppText.text(R.string.copy_177d9ac631dd, "Make Coach more conversational"),
        subtitle = AppText.text(R.string.copy_cd536acafa72, "Add optional AI for conversations on your phone."),
        onBack = { navController.popBackStack() },
        footer = {
            if (done) PrimaryButton(AppText.text(R.string.copy_31fbef162594, "Continue"), onClick = next)
            else {
                PrimaryButton(if (busy) AppText.text(R.string.copy_339a42865ec7, "Getting Coach ready…") else if (progress is DownloadProgress.Failure) AppText.text(R.string.copy_d8b8392e2c54, "Try again") else AppText.text(R.string.copy_003977f0b57d, "Download now"),
                    onClick = vm::startDownload, loading = busy)
                Spacer(Modifier.height(Space.s))
                GhostButton(if (busy) AppText.text(R.string.copy_dc14e084903b, "Stop and skip") else AppText.text(R.string.copy_b58eb52c8810, "Skip for now"),
                    onClick = { if (busy) vm.stopDownload(); next() }, modifier = Modifier.fillMaxWidth())
            }
        }
    ) {
        BipssCard {
            FeatureRow(Icons.Rounded.Forum, MaterialTheme.colorScheme.primary, AppText.text(R.string.copy_aa77c232d64c, "More room for conversation"),
                AppText.text(R.string.copy_4237f9beac3d, "Ask follow-up questions about your day and explore your readings."))
            Spacer(Modifier.height(Space.xl))
            FeatureRow(Icons.Rounded.TipsAndUpdates, BipssTheme.colors.sleep, AppText.text(R.string.copy_485ca594ccc9, "Private by design"),
                AppText.text(R.string.copy_b81b488d3243, "Your conversations stay on your phone."))
        }
        Spacer(Modifier.height(Space.l))
        Text(AppText.text(R.string.copy_0e14d6d8c3b8, "Download on Wi-Fi if possible. You can keep using Coach and add AI later."),
            style = MaterialTheme.typography.bodyMedium, color = BipssTheme.colors.textSecondary)
        if (progress !is DownloadProgress.Idle) {
            Spacer(Modifier.height(Space.l))
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
                        AppText.text(R.string.copy_4faf4bdff501, "%1\$s / %2\$s MB", progress.mbDownloaded, progress.totalMb),
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
                    (if (progress.estimatedSecondsRemaining > 0) AppText.text(R.string.copy_42cdcbf90378, "About %1\$ss remaining · ", progress.estimatedSecondsRemaining) else "") +
                        AppText.text(R.string.copy_758cfd9b1af7, "keep BIPSS open while it downloads"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
            }

            is DownloadProgress.Initializing -> IndeterminateRow(AppText.text(R.string.copy_f8a13f993494, "Checking for the latest model…"))
            is DownloadProgress.Verifying -> IndeterminateRow(AppText.text(R.string.copy_15e433ec884f, "Checking your download…"))
            is DownloadProgress.Preparing -> IndeterminateRow(AppText.text(R.string.copy_7800094e7601, "Preparing Coach…"))

            is DownloadProgress.Success, is DownloadProgress.AlreadyUpToDate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.CheckCircle, colors.bandNormal)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppText.text(R.string.copy_996f3eb220d1, "Model ready"),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            AppText.text(R.string.copy_2c9a14b22244, "The Coach tab now answers with the on-device model."),
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
                            AppText.text(R.string.copy_eae34ccbc443, "Download failed"),
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
