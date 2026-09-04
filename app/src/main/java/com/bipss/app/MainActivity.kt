package com.bipss.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

            BipssTheme(themePreference = themePref) {
                if (!mainState.isLoading) {
                    AppShell(startDestination = mainState.startDestination)
                }
            }
        }
    }
}
