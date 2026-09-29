package com.bipss.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.SectionLabelStyle
import com.bipss.app.ui.theme.Space

/** Standard content card: one step up the surface ladder plus a hairline edge. */
@Composable
fun BipssCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Space.l),
    accent: Color? = null,
    /**
     * Fill the height the parent gives it, content spread top to bottom - for
     * cards side by side in a row of equal height (see IntrinsicSize.Min).
     */
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = BipssTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val border = BorderStroke(1.dp, accent?.copy(alpha = 0.35f) ?: colors.hairline)
    val inner = if (fillHeight) Modifier.fillMaxHeight().padding(contentPadding) else Modifier.padding(contentPadding)
    val arrangement = if (fillHeight) Arrangement.SpaceBetween else Arrangement.Top

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = colors.card,
            border = border,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(inner, verticalArrangement = arrangement, content = content)
        }
    } else {
        Surface(
            shape = shape,
            color = colors.card,
            border = border,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(inner, verticalArrangement = arrangement, content = content)
        }
    }
}

/** Uppercase tracked group heading, e.g. "MY DEVICE". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = SectionLabelStyle,
        color = BipssTheme.colors.textTertiary,
        modifier = modifier
    )
}

/** A section heading with optional trailing slot, for scrollable pages. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = BipssTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** Small status chip — colour carries the meaning. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
) {
    // On a filled pill the text takes whichever of black or white reads on the
    // fill: a white fill (the dark theme's primary) made white text vanish.
    val onFill = if (color.luminance() > 0.5f) Color.Black else Color.White
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon, null,
                tint = if (filled) onFill else color,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) onFill else color
        )
    }
}

/**
 * Equal-width choices in one rounded track; the chosen one is a solid thumb.
 * Screen readers hear it as a set of tabs with the current one selected.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BipssTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.field)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.textPrimary else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.background else colors.textSecondary
                )
            }
        }
    }
}

/** A small circular icon badge in a tinted well — used across cards and rows. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Inline explanatory / warning banner. */
@Composable
fun InfoBanner(
    text: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.m))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = BipssTheme.colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Spacer(Modifier.width(Space.s))
            Box(Modifier.wrapContentWidth()) { action() }
        }
    }
}

/** Centered empty/zero state. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = BipssTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(icon, colors.textTertiary, size = 56.dp)
        Spacer(Modifier.height(Space.l))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Spacer(Modifier.height(Space.s))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(Space.l))
            action()
        }
    }
}

/** Label/value row used inside cards. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = BipssTheme.colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = BipssTheme.colors.textPrimary
        )
    }
}

/** Hairline divider matching the card edge. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BipssTheme.colors.hairline)
    )
}
