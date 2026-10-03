package com.bipss.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.localization.AppText
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.components.HairlineDivider
import com.bipss.app.ui.components.SecondaryButton
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Taking your data with you, and the Monday report notification. */
@Composable
fun YourDataCard(nav: NavController, prefs: UserPreferencesRepository = koinInject()) {
    val colors = BipssTheme.colors
    val scope = rememberCoroutineScope()
    val weekly by prefs.weeklyReportNotification.collectAsStateWithLifecycle(true)
    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppText.text(R.string.copy_28c2536bb6c7, "Weekly report notification"), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Text(AppText.text(R.string.copy_bb8db588c1f0, "Monday morning, when last week has a report"),
                    style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Switch(checked = weekly, onCheckedChange = { scope.launch { prefs.setWeeklyReportNotification(it) } })
        }
        Spacer(Modifier.height(Space.m))
        HairlineDivider()
        Spacer(Modifier.height(Space.m))
        SecondaryButton(AppText.text(R.string.copy_9c80b04be554, "Export your data"), onClick = { nav.navigate(Routes.EXPORT) }, modifier = Modifier.fillMaxWidth())
    }
}
