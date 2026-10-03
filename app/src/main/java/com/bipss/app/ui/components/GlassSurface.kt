package com.bipss.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * iOS-style bar material, used only by the floating tab bar: a solid
 * system fill (Android has no cheap backdrop blur, and a see-through bar let
 * the cards behind show through its labels), a hairline edge and a soft shadow, tuned separately for
 * light and dark so neither theme looks washed out.
 */
@Composable
fun Modifier.glassSurface(radius: Int = 24): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(radius.dp)
    val fill = if (dark) Color(0xFF1C1C1E) else Color.White
    val edge = if (dark) Color.White.copy(alpha = .12f) else Color.Black.copy(alpha = .10f)
    val shadow = if (dark) Color.Black.copy(alpha = .6f) else Color.Black.copy(alpha = .30f)
    return this.shadow(14.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
        .clip(shape).background(fill).border(0.5.dp, edge, shape)
}
