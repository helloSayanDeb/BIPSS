package com.bipss.app.ui.planner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bipss.app.R
import com.bipss.app.localization.AppText
import com.bipss.app.notifications.BipssNotifications
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

@Composable
internal fun ReminderCard(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppText.text(R.string.copy_df40a9135d67, "Bedtime reminder"), style = MaterialTheme.typography.titleSmall)
                Text(AppText.text(R.string.copy_b0d8a87ede75, "A nudge 30 minutes before bed"),
                    style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
            }
            Switch(checked = on, onCheckedChange = onChange)
        }
        if (on && !BipssNotifications.hasPermission(context)) {
            Spacer(Modifier.height(Space.s))
            Text(AppText.text(R.string.copy_bf72c0f7da6d, "Notifications are off for BIPSS in Android settings, so this reminder will not show."),
                style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.bandMonitor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WakeTimeDialog(wakeMin: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val picker = rememberTimePickerState(initialHour = wakeMin / 60, initialMinute = wakeMin % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppText.text(R.string.copy_27b9aac0c836, "Wake time")) },
        text = { TimePicker(state = picker) },
        confirmButton = { TextButton(onClick = { onSave(picker.hour * 60 + picker.minute) }) { Text(AppText.text(R.string.copy_1509f561f241, "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel")) } }
    )
}
