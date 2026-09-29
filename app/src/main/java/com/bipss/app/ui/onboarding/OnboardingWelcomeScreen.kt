package com.bipss.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.BrandMark
import com.bipss.app.ui.components.FeatureRow
import com.bipss.app.ui.components.PrimaryButton
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

@Composable
fun OnboardingWelcomeScreen(navController: NavController) {
    val colors = BipssTheme.colors

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Soft brand glow behind the mark, keeps the top of the page from
        // reading as a flat slab.
        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(Brush.verticalGradient(colors.heroGlow))
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
        ) {
            Spacer(Modifier.height(Space.huge))
            BrandMark(size = 80.dp)

            Spacer(Modifier.height(Space.xxl))
            Text(
                "BIPSS",
                style = MaterialTheme.typography.displayLarge,
                color = colors.textPrimary
            )
            Spacer(Modifier.height(Space.s))
            Text(
                "Your health and safety companion — reading your band, your surroundings, and the risks in between.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary
            )

            Spacer(Modifier.height(Space.huge))

            FeatureRow(
                icon = Icons.Rounded.MonitorHeart,
                tint = colors.heart,
                title = "Vitals that learn you",
                body = "Heart rate, SpO₂ and temperature judged against your own baseline, not a textbook average."
            )
            Spacer(Modifier.height(Space.xl))
            FeatureRow(
                icon = Icons.Rounded.Sensors,
                tint = colors.air,
                title = "Works offline",
                body = "Your band's readings and scores work without a connection. Weather, air quality and alerts refresh when you're back online."
            )
            Spacer(Modifier.height(Space.xl))
            FeatureRow(
                icon = Icons.Rounded.Shield,
                tint = colors.bandNormal,
                title = "Private by default",
                body = "Analysis runs on your phone. Your health data leaves it only if you switch on sharing or backup."
            )

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(Space.huge))

            PrimaryButton(
                text = "Get started",
                onClick = { navController.navigate(Routes.ONBOARDING_NOTIFICATIONS) }
            )
            Spacer(Modifier.height(Space.m))
            Text(
                "Seven short steps. Anything optional can be skipped and set up later.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Space.xxl))
        }
    }
}
