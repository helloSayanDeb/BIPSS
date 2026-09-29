package com.bipss.app.ui.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ble.BleConnectionState
import com.bipss.app.sos.openAppSettings
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import org.koin.androidx.compose.koinViewModel

/** What stands between this screen and a scan, in the order the user has to fix them. */
private enum class ScanBlocker { NO_BLUETOOTH, NO_PERMISSION, PERMISSION_BLOCKED, BLUETOOTH_OFF }

/**
 * Step 3: the band. Also opened from Settings to re-pair or add a band.
 * Nearby-devices permission is asked once on arrival; after a refusal the
 * screen offers to ask again, or - once Android stops showing the dialog -
 * to open the app's settings. Bluetooth being off is its own state, with a
 * button that asks Android to turn it on, and the scan starts as soon as it is.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingConnectBandScreen(
    navController: NavController,
    isRePairMode:  Boolean = false,
    vm: BleScanViewModel = koinViewModel()
) {
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val bluetoothOn by vm.bluetoothOn.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val context = LocalContext.current

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    // "Answered" rather than "asked": before the first answer, no rationale is
    // the normal state, not a permanent refusal.
    var asked by rememberSaveable { mutableStateOf(false) }
    var answered by rememberSaveable { mutableStateOf(false) }
    val permissionState = rememberMultiplePermissionsState(permissions) { answered = true }
    val request: () -> Unit = { asked = true; permissionState.launchMultiplePermissionRequest() }

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshBluetoothState() }

    LifecycleResumeEffect(Unit) {
        vm.refreshBluetoothState()
        onPauseOrDispose { }
    }

    val blocker = when {
        !vm.hasBluetooth -> ScanBlocker.NO_BLUETOOTH
        !permissionState.allPermissionsGranted ->
            if (answered && permissionState.permissions.none { it.status.shouldShowRationale }) ScanBlocker.PERMISSION_BLOCKED
            else ScanBlocker.NO_PERMISSION
        !bluetoothOn -> ScanBlocker.BLUETOOTH_OFF
        else -> null
    }

    LaunchedEffect(permissionState.allPermissionsGranted, bluetoothOn) {
        when {
            permissionState.allPermissionsGranted && bluetoothOn -> vm.startScan()
            !permissionState.allPermissionsGranted && !asked && vm.hasBluetooth -> request()
        }
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
        else navController.goToNextOnboardingStep(OnboardingStep.BAND, catchUp = false)
    }

    OnboardingScaffold(
        step = if (isRePairMode) null else OnboardingStep.indexOf(OnboardingStep.BAND, catchUp = false),
        totalSteps = OnboardingStep.TOTAL,
        title = if (isRePairMode) "Re-pair your band" else "Connect your band",
        subtitle = "Power the BIPSS band on and hold it near your phone.",
        onBack = { navController.popBackStack() },
        footer = {
            PrimaryButton(
                text = when {
                    isConnected -> "Continue"
                    blocker != null -> "No band connected"
                    else -> "Waiting for band…"
                },
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
                    blocker != null -> Icons.Rounded.BluetoothDisabled
                    else -> Icons.Rounded.Watch
                },
                tint = if (isConnected) colors.bandNormal else MaterialTheme.colorScheme.primary,
                active = blocker == null && !isConnected,
            )
        }

        Spacer(Modifier.height(Space.xl))

        when (blocker) {
            ScanBlocker.NO_BLUETOOTH -> InfoBanner(
                text = "This phone has no Bluetooth, so it can't connect to a band. You can skip this step.",
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor
            )
            ScanBlocker.NO_PERMISSION -> InfoBanner(
                text = "BIPSS needs the nearby-devices permission to find your band.",
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor,
                action = { GhostButton(text = "Allow", onClick = request, color = colors.bandMonitor) }
            )
            ScanBlocker.PERMISSION_BLOCKED -> InfoBanner(
                text = "Nearby-devices permission is turned off for BIPSS. Allow it in Android's settings to find your band.",
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor,
                action = { GhostButton(text = "Settings", onClick = { openAppSettings(context) }, color = colors.bandMonitor) }
            )
            ScanBlocker.BLUETOOTH_OFF -> InfoBanner(
                text = "Bluetooth is off. Searching starts as soon as it's on.",
                icon = Icons.Rounded.BluetoothDisabled,
                tint = colors.bandMonitor,
                action = {
                    GhostButton(
                        text = "Turn on",
                        onClick = {
                            runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        },
                        color = colors.bandMonitor
                    )
                }
            )
            null -> when (val s = state) {
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

                else -> StatusRow("Searching for nearby bands…", showSpinner = true)
            }
        }

        Spacer(Modifier.height(Space.xl))
        AnimatedVisibility(visible = !isConnected && !isRePairMode, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "No band yet? Skip this step. You can pair one later in Settings.",
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
