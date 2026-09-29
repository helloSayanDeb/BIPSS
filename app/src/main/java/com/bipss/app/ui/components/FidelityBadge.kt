package com.bipss.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bipss.app.domain.Fidelity
import com.bipss.app.ui.theme.BipssTheme

/** The fidelity of a metric, as a small pill. Screen readers get the explanation, not just the word. */
@Composable
fun FidelityBadge(fidelity: Fidelity, modifier: Modifier = Modifier) {
    StatusPill(
        text = fidelity.label.uppercase(),
        color = fidelityColor(fidelity),
        modifier = modifier.semantics { contentDescription = "${fidelity.label}. ${fidelity.explanation}" }
    )
}

@Composable
fun fidelityColor(fidelity: Fidelity): Color {
    val colors = BipssTheme.colors
    return when (fidelity) {
        Fidelity.PRODUCTION   -> colors.bandNormal
        Fidelity.ESTIMATE     -> colors.textSecondary
        Fidelity.EXPERIMENTAL -> colors.bandMonitor
        Fidelity.ROADMAP      -> colors.textTertiary
    }
}
