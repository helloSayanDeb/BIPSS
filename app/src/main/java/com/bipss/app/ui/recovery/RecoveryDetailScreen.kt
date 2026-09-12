package com.bipss.app.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryDetailScreen(
    navController: NavController,
    vm: RecoveryDetailViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recovery") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(pad)
                .padding(horizontal = Space.gutter)
        ) {
            Spacer(Modifier.height(Space.m))

            val breakdown = state.breakdown
            if (breakdown == null || breakdown.totalScore == null) {
                val missing = breakdown?.missingInputs?.joinToString(" and ") ?: "sensor data"
                EmptyState(
                    icon = Icons.Rounded.ArrowBack, // Placeholder icon
                    title = "Recovery unavailable",
                    body = "Recovery requires $missing to compute. Wear the band or wait for more history."
                )
            } else {
                BipssCard {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${breakdown.totalScore}",
                            style = MetricValue,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.width(Space.m))
                        Text(
                            "out of 100",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(Space.m))
                    
                    breakdown.components.forEachIndexed { index, comp ->
                        if (index > 0) HairlineDivider()
                        InfoRow(comp.label, comp.summary)
                    }
                }
            }
            
            Spacer(Modifier.height(Space.xxl))
        }
    }
}
