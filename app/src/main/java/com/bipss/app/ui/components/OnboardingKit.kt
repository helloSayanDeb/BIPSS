package com.bipss.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

/**
 * Shared onboarding page frame: back affordance + step indicator up top,
 * scrollable body, and a sticky footer so the primary action is always
 * reachable without hunting for it at the bottom of a scroll.
 */
@Composable
fun OnboardingScaffold(
    step: Int?,
    totalSteps: Int,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BipssTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.s, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                        tint = colors.textSecondary
                    )
                }
            } else {
                Spacer(Modifier.width(Space.l))
            }
            Spacer(Modifier.weight(1f))
            if (step != null) {
                StepProgress(current = step, total = totalSteps)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
        ) {
            Spacer(Modifier.height(Space.l))
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textPrimary
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Space.s))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary
                )
            }
            Spacer(Modifier.height(Space.xxl))
            content()
            Spacer(Modifier.height(Space.xxl))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.l)
                .navigationBarsPadding(),
            content = footer
        )
    }
}

/** Segmented step indicator — filled bars for done, tinted for pending. */
@Composable
fun StepProgress(current: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = BipssTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val done = i <= current
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (i == current) 24.dp else 14.dp)
                    .clip(CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else colors.raised)
            )
        }
    }
}

/** The BIPSS pulse mark, drawn to match the launcher icon. */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val colors = BipssTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(Brush.linearGradient(colors.brandGradient)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * 0.62f)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = w * 0.10f
            val path = Path().apply {
                moveTo(0f, h * 0.55f)
                lineTo(w * 0.22f, h * 0.55f)
                lineTo(w * 0.36f, h * 0.20f)
                lineTo(w * 0.54f, h * 0.88f)
                lineTo(w * 0.68f, h * 0.42f)
                lineTo(w * 0.78f, h * 0.55f)
                lineTo(w, h * 0.55f)
            }
            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/** Icon + title + body row for value propositions on the welcome screen. */
@Composable
fun FeatureRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val colors = BipssTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        IconBadge(icon, tint, size = 42.dp)
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}

/**
 * Concentric radar pulse used while scanning for the band. Three rings expand
 * and fade on a staggered loop with the given icon held at the centre.
 */
@Composable
fun ScanPulse(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    size: Dp = 168.dp,
) {
    val transition = rememberInfiniteTransition(label = "scan")
    val colors = BipssTheme.colors

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (active) {
            repeat(3) { index ->
                val progress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2400, easing = LinearEasing),
                        initialStartOffset = StartOffset(index * 800)
                    ),
                    label = "ring$index"
                )
                Canvas(Modifier.fillMaxSize()) {
                    val maxR = this.size.minDimension / 2f
                    drawCircle(
                        color = tint.copy(alpha = (1f - progress) * 0.35f),
                        radius = maxR * (0.36f + 0.64f * progress),
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
        Box(
            Modifier
                .size(size * 0.42f)
                .clip(CircleShape)
                .background(if (active) tint.copy(alpha = 0.16f) else colors.raised),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = if (active) tint else colors.textTertiary,
                modifier = Modifier.size(size * 0.19f)
            )
        }
    }
}
