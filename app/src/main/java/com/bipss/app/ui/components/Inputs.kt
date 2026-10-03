package com.bipss.app.ui.components

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

/**
 * The app's standard text field.
 *
 * Deliberately not Material's OutlinedTextField: the label sits *above* the
 * box rather than floating through the border, and the box has a filled
 * container one step up the surface ladder. Both choices exist so the field
 * is unmistakably visible on a dark page — the previous outlined-on-dark
 * fields were effectively invisible.
 */
@Composable
fun BipssTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suffix: String? = null,
    leadingIcon: ImageVector? = null,
    error: String? = null,
    helper: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    val colors = BipssTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val borderColor by animateColorAsState(
        targetValue = when {
            error != null -> colors.bandRisk
            focused       -> MaterialTheme.colorScheme.primary
            else          -> colors.borderStrong.copy(alpha = 0.55f)
        },
        label = "fieldBorder"
    )

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (error != null) colors.bandRisk else colors.textSecondary,
        )
        Spacer(Modifier.height(Space.s))

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(colors.field, RoundedCornerShape(14.dp))
                        .border(
                            width = if (focused || error != null) 1.5.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = Space.l),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            leadingIcon, null,
                            tint = if (focused) MaterialTheme.colorScheme.primary else colors.textTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Space.m))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textTertiary
                            )
                        }
                        innerField()
                    }
                    if (suffix != null) {
                        Spacer(Modifier.width(Space.s))
                        Text(
                            suffix,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textTertiary
                        )
                    }
                }
            }
        )

        val support = error ?: helper
        if (support != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                support,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) colors.bandRisk else colors.textTertiary
            )
        }
    }
}

/** Full-width primary action. 56dp tall, brand-filled. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val colors = BipssTheme.colors
    val container = if (enabled && !loading) MaterialTheme.colorScheme.primary else colors.raised
    val content = if (enabled && !loading) MaterialTheme.colorScheme.onPrimary else colors.textTertiary

    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier.fillMaxWidth().height(56.dp)
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = content
                )
            } else {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, tint = content, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.s))
                }
                Text(text, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}

/** Outlined secondary action. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    contentColor: Color? = null,
) {
    val colors = BipssTheme.colors
    val tint = contentColor ?: colors.textPrimary
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) colors.borderStrong else colors.hairline),
        modifier = modifier.height(52.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = Space.l),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = if (enabled) tint else colors.textTertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.s))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) tint else colors.textTertiary
            )
        }
    }
}

/** Low-emphasis inline action ("Skip for now"). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val tint = color ?: BipssTheme.colors.textSecondary
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = modifier.height(48.dp)
    ) {
        // fillMaxHeight (not fillMaxSize): inside a Row this button must size
        // to its text, otherwise it swallows the width of a weighted sibling.
        Box(
            Modifier.fillMaxHeight().padding(horizontal = Space.m),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides tint) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
            }
        }
    }
}
