package com.bipss.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ble.BleConnectionState
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingConnectBandScreen(
    navController: NavController,
    isRePairMode:  Boolean = false,
    vm: BleScanViewModel = koinViewModel()
) {
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted) vm.startScan()
        else permissionState.launchMultiplePermissionRequest()
    }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    // Remember the last device we saw so the pairing can be persisted the
    // moment the GATT connection is confirmed.
    var lastSeen by remember { mutableStateOf<BleConnectionState.DeviceFound?>(null) }
    LaunchedEffect(state) {
        (state as? BleConnectionState.DeviceFound)?.let { lastSeen = it }
        if (state is BleConnectionState.Connected) {
            lastSeen?.let { vm.savePairedDevice(mac = it.address, name = it.name) }
        }
    }

    val isConnected = state is BleConnectionState.Connected
    val goNext: () -> Unit = {
        if (isRePairMode) navController.popBackStack()
        else navController.navigate(Routes.ONBOARDING_DOWNLOAD_MODEL)
    }

    OnboardingScaffold(
        step = if (isRePairMode) null else 1,
        totalSteps = 3,
        title = if (isRePairMode) "Re-pair your band" else "Connect your band",
        subtitle = "Power the BIPSS band on and hold it near your phone.",
        onBack = { navController.popBackStack() },
        footer = {
            PrimaryButton(
                text = if (isConnected) "Continue" else "Waiting for band…",
                enabled = isConnected,
                onClick = goNext
            )
            if (!isRePairMode) {
                Spacer(Modifier.height(Space.s))
                GhostButton(
                    text = "Skip for now",
                    onClick = goNext,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ScanPulse(
                icon = when {
                    isConnected -> Icons.Rounded.CheckCircle
                    !permissionState.allPermissionsGranted -> Icons.Rounded.BluetoothDisabled
                    else -> Icons.Rounded.Watch
                },
                tint = if (isConnected) colors.bandNormal else MaterialTheme.colorScheme.primary,
                active = permissionState.allPermissionsGranted && !isConnected,
            )
        }

        Spacer(Modifier.height(Space.xl))

        when (val s = state) {
            is BleConnectionState.DeviceFound -> {
                BipssCard(accent = MaterialTheme.colorScheme.primary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Bluetooth, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                "${s.address}  ·  ${s.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary
                            )
                        }
                        SecondaryButton(
                            text = "Connect",
                            onClick = { vm.connectToDevice(s.address) },
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            is BleConnectionState.Connecting -> StatusRow("Connecting to your band…", showSpinner = true)

            is BleConnectionState.Connected -> {
                BipssCard(accent = colors.bandNormal) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.CheckCircle, colors.bandNormal)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                lastSeen?.name ?: "BIPSS Band",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                "Paired and streaming",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.bandNormal
                            )
                        }
                    }
                }
            }

            is BleConnectionState.Error -> InfoBanner(
                text = s.message,
                icon = Icons.Rounded.Info,
                tint = colors.bandRisk
            )

            else -> {
                if (!permissionState.allPermissionsGranted) {
                    InfoBanner(
                        text = "Bluetooth permission is needed to find your band.",
                        icon = Icons.Rounded.Info,
                        tint = colors.bandMonitor,
                        action = {
                            GhostButton(
                                text = "Allow",
                                onClick = { permissionState.launchMultiplePermissionRequest() },
                                color = colors.bandMonitor
                            )
                        }
                    )
                } else {
                    StatusRow("Searching for nearby bands…", showSpinner = true)
                }
            }
        }

        Spacer(Modifier.height(Space.xl))
        AnimatedVisibility(visible = !isConnected, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "No band yet? Skip this — BIPSS still shows local weather, air quality and alerts, and you can pair a band later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatusRow(text: String, showSpinner: Boolean) {
    val colors = BipssTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colors.textTertiary
            )
            Spacer(Modifier.width(Space.m))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}
