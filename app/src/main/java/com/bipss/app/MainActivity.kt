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
import com.bipss.app.navigation.DeepLink
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold start from a notification tap. Skipped on recreation so a
        // rotation does not replay a tap that was already handled.
        if (savedInstanceState == null) {
            mainViewModel.onDeepLink(DeepLink.fromIntent(intent))
            intent?.removeExtra(DeepLink.EXTRA_NAVIGATE_TO)
        }
        setContent {
            val mainVm:     MainViewModel    = koinViewModel()
            val settingsVm: SettingsViewModel = koinViewModel()
            val deepLink    by mainVm.pendingDeepLink.collectAsStateWithLifecycle()

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
                    AppShell(
                        startDestination   = mainState.startDestination,
                        deepLink           = deepLink,
                        onDeepLinkConsumed = mainVm::consumeDeepLink
                    )
                }
            }
        }
    }

    /** A tap while the activity already exists arrives here (SINGLE_TOP), not in onCreate. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        mainViewModel.onDeepLink(DeepLink.fromIntent(intent))
        intent.removeExtra(DeepLink.EXTRA_NAVIGATE_TO)
        setIntent(intent)
    }
}
