package com.bipss.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bipss.app.data.WORKOUT_TYPES
import com.bipss.app.localization.AppText
import com.bipss.app.ui.theme.Space

/**
 * The activity types to choose from. The value passed back is always the
 * English key that is stored; only the label follows the phone's language
 * (storing the translated label made logging fail outside English).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityTypeChooser(selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        WORKOUT_TYPES.forEach { key ->
            FilterChip(selected = selected == key, onClick = { onSelect(key) }, label = { Text(AppText.label(key)) })
        }
    }
}
