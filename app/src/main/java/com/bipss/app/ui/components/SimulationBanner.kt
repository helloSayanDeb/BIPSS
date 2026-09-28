package com.bipss.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bipss.app.ui.theme.BipssTheme

/**
 * Shown on every screen while the debug band simulator is the data source, so
 * a screenshot of simulated numbers can never pass for real ones.
 */
@Composable
fun SimulationBanner(modifier: Modifier = Modifier) {
    val colors = BipssTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bandMonitor)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Science, contentDescription = null, tint = colors.card, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Simulated band — not real data",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.card
        )
    }
}
