package com.bipss.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bipss.app.ui.settings.SettingsViewModel
import com.bipss.app.ui.shell.AppShell
import com.bipss.app.ui.theme.BipssTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainVm:     MainViewModel    = koinViewModel()
            val settingsVm: SettingsViewModel = koinViewModel()

            val mainState   by mainVm.uiState.collectAsStateWithLifecycle()
            val themePref   by settingsVm.themePreference.collectAsStateWithLifecycle()

            // Android 13+ needs an explicit grant before any alert, coaching or
            // reminder notification can be posted. Asked once at launch; a
            // refusal degrades to a logged no-op rather than a crash.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* granted or not - BipssNotifications handles both */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            BipssTheme(themePreference = themePref) {
                if (!mainState.isLoading) {
                    AppShell(startDestination = mainState.startDestination)
                }
            }
        }
    }
}
