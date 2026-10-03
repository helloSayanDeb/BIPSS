package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bipss.app.domain.*
import com.bipss.app.ui.components.StatusPill
import com.bipss.app.ui.theme.BipssTheme

@Composable internal fun scoreColor(score: RiskScore): Color = when {
    !score.hasNumber -> BipssTheme.colors.textTertiary
    score.type == RiskScoreType.STRAIN -> BipssTheme.colors.activity
    score.type.category == ScoreCategory.EXPERIMENTAL -> BipssTheme.colors.textSecondary
    else -> score.level.bandColor()
}
@Composable internal fun ScoreBadges(score: RiskScore) {
    if (score.provisional) StatusPill(AppText.text(R.string.copy_36fa7ab75b6f, "Building your baseline"), BipssTheme.colors.bandMonitor)
}
@Composable internal fun RiskLevel.bandColor(): Color = when (this) {
    RiskLevel.LOW -> BipssTheme.colors.bandNormal
    RiskLevel.MEDIUM -> BipssTheme.colors.bandMonitor
    RiskLevel.HIGH -> BipssTheme.colors.bandRisk
}
internal fun RiskLevel.bandLabel(): String = when (this) {
    RiskLevel.LOW -> AppText.text(R.string.copy_f793de205ead, "Low")
    RiskLevel.MEDIUM -> AppText.text(R.string.copy_5c42afc7a230, "Moderate")
    RiskLevel.HIGH -> AppText.text(R.string.copy_c4ebc6d4a583, "High")
}

internal fun scoreStatus(score: RiskScore): String = score.levelLabel?.let(AppText::display) ?: if (score.type.isHazard) {
    score.level.bandLabel()
} else when (score.level) {
    RiskLevel.LOW -> AppText.text(R.string.copy_c939327ca16d, "Good")
    RiskLevel.MEDIUM -> AppText.text(R.string.copy_f7b19afcde96, "Fair")
    RiskLevel.HIGH -> AppText.text(R.string.copy_f793de205ead, "Low")
}
