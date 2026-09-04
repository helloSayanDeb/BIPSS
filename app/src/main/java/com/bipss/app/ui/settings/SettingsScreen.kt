package com.bipss.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.BuildConfig
import com.bipss.app.ai.DownloadProgress
import com.bipss.app.data.db.entity.EmergencyContactEntity
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import com.bipss.app.ui.theme.ThemePreference
import com.google.accompanist.permissions.*
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    if (state.showDeleteLocalDataDialog) DeleteDataDialog(state, vm)
    if (state.showForgetDeviceDialog)    ForgetDeviceDialog(vm)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = colors.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = colors.textPrimary
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
        ) {
            Group("Profile") { ProfileCard(state, navController) }
            Group("My device") { DeviceCard(state, vm, navController) }
            Group("AI model") { AiModelCard(state, vm) }
            Group("Appearance") { ThemeSelector(state.themePreference, vm::onThemePreferenceSelected) }
            Group("Privacy & data") { PrivacySection(state, vm) }
            Group("Emergency contacts") { EmergencyContacts(state, vm, navController) }
            Group("About") { AboutCard() }
            Spacer(Modifier.height(Space.huge))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(Space.xxl))
    SectionLabel(title)
    Spacer(Modifier.height(Space.m))
    content()
}

// ── Profile ─────────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(state: SettingsUiState, navController: NavController) {
    val colors = BipssTheme.colors
    BipssCard {
        if (state.userName.isNotBlank()) {
            InfoRow("Name", state.userName)
            HairlineDivider()
            InfoRow("Age", "${state.userAge} years")
            HairlineDivider()
            InfoRow("Height", "${state.userHeightCm.toInt()} cm")
            HairlineDivider()
            InfoRow("Weight", "${state.userWeightKg} kg")
        } else {
            Text(
                "No profile set up yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }
        Spacer(Modifier.height(Space.l))
        SecondaryButton(
            text = "Edit profile",
            leadingIcon = Icons.Rounded.Edit,
            onClick = { navController.navigate(Routes.ONBOARDING_PROFILE_EDIT) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Device ──────────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(state: SettingsUiState, vm: SettingsViewModel, navController: NavController) {
    val colors = BipssTheme.colors
    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Watch, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    state.bandName ?: "No device paired",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = if (state.isBandConnected) colors.bandNormal else colors.textTertiary
                    Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            state.isBandConnected -> "Connected"
                            state.bandMac != null -> "Disconnected"
                            else                  -> "Not paired"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.l))
        if (state.bandMac != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!state.isBandConnected) {
                    SecondaryButton("Reconnect", { vm.onReconnectTap() }, Modifier.weight(1f))
                }
                SecondaryButton(
                    "Re-pair",
                    { navController.navigate(Routes.ONBOARDING_CONNECT_BAND_REPAIR) },
                    Modifier.weight(1f)
                )
                SecondaryButton(
                    "Forget", { vm.showForgetDialog() }, Modifier.weight(1f),
                    contentColor = colors.bandRisk
                )
            }
        } else {
            PrimaryButton(
                text = "Add device",
                leadingIcon = Icons.Rounded.Bluetooth,
                onClick = { navController.navigate(Routes.ONBOARDING_CONNECT_BAND_REPAIR) }
            )
        }
    }
}

// ── AI model ────────────────────────────────────────────────────────────────

@Composable
private fun AiModelCard(state: SettingsUiState, vm: SettingsViewModel) {
    val colors = BipssTheme.colors
    BipssCard {
        if (state.modelVersion != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Model installed",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        "Version ${state.modelVersion} · ${state.modelSizeMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SecondaryButton(
                    text = if (state.isCheckingUpdate) "Checking…" else "Check for updates",
                    onClick = { vm.onCheckForUpdates() },
                    enabled = !state.isCheckingUpdate,
                    modifier = Modifier.weight(1f)
                )
                if (state.updateAvailable) {
                    SecondaryButton(
                        "Update", { vm.onDownloadModelTap() }, Modifier.weight(1f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Text(
                "The on-device model isn't installed. Chat falls back to rule-based replies, and every other feature works normally.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(Space.l))
            when (val p = state.downloadPhase) {
                is DownloadProgress.Downloading -> {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.raised,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Spacer(Modifier.height(Space.s))
                    Text(
                        "${(p.fraction * 100).toInt()}%  ·  ${p.mbDownloaded}/${p.totalMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
                is DownloadProgress.Verifying -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.raised
                    )
                    Spacer(Modifier.height(Space.s))
                    Text("Verifying checksum…", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
                else -> PrimaryButton("Download AI model", { vm.onDownloadModelTap() })
            }
        }
    }
}

// ── Appearance ──────────────────────────────────────────────────────────────

@Composable
private fun ThemeSelector(current: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    val colors = BipssTheme.colors
    BipssCard(contentPadding = PaddingValues(vertical = Space.xs)) {
        ThemePreference.entries.forEachIndexed { i, pref ->
            val selected = pref == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(pref) }
                    .padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (pref) {
                        ThemePreference.SYSTEM -> Icons.Rounded.PhoneAndroid
                        ThemePreference.LIGHT  -> Icons.Rounded.LightMode
                        ThemePreference.DARK   -> Icons.Rounded.DarkMode
                    },
                    null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else colors.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Space.m))
                Text(
                    pref.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                RadioButton(
                    selected = selected,
                    onClick = { onSelect(pref) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = colors.borderStrong
                    )
                )
            }
            if (i < ThemePreference.entries.lastIndex) {
                Box(Modifier.padding(horizontal = Space.l)) { HairlineDivider() }
            }
        }
    }
}

// ── Privacy ─────────────────────────────────────────────────────────────────

@Composable
private fun PrivacySection(state: SettingsUiState, vm: SettingsViewModel) {
    val colors = BipssTheme.colors
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* re-tap Back up / Restore after granting access */ }

    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cloud backup", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(
                    "Settings, baselines and contacts only — raw sensor data never leaves the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Spacer(Modifier.width(Space.m))
            Switch(
                checked = state.cloudBackupEnabled,
                onCheckedChange = { vm.onCloudBackupToggle(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
        if (state.cloudBackupEnabled) {
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SecondaryButton(
                    text = if (state.isBackingUp) "Backing up…" else "Back up now",
                    onClick = { vm.onBackupNowTap { intent -> signInLauncher.launch(intent) } },
                    enabled = !state.isBackingUp,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = if (state.isRestoring) "Restoring…" else "Restore",
                    onClick = { vm.onRestoreTap { intent -> signInLauncher.launch(intent) } },
                    enabled = !state.isRestoring,
                    modifier = Modifier.weight(1f)
                )
            }
            state.backupStatusMessage?.let {
                Spacer(Modifier.height(Space.s))
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            }
        }
    }

    if (state.deletionDaysRemaining != null) {
        Spacer(Modifier.height(Space.m))
        InfoBanner(
            text = "Cloud deletion completes in ${state.deletionDaysRemaining} day(s). You can still cancel before then.",
            icon = Icons.Rounded.HourglassEmpty,
            tint = colors.bandMonitor
        )
    }

    Spacer(Modifier.height(Space.m))
    SecondaryButton(
        text = "Delete my data",
        leadingIcon = Icons.Rounded.DeleteForever,
        onClick = { vm.onDeleteDataTap() },
        contentColor = colors.bandRisk,
        modifier = Modifier.fillMaxWidth()
    )
}

// ── Emergency contacts ──────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun EmergencyContacts(
    state: SettingsUiState,
    vm: SettingsViewModel,
    navController: NavController
) {
    val colors = BipssTheme.colors
    val smsPerm = rememberMultiplePermissionsState(listOf(android.Manifest.permission.SEND_SMS))
    val locPerm = rememberPermissionState(android.Manifest.permission.ACCESS_FINE_LOCATION)

    Text(
        "Contacts here receive an SMS with your location when SOS fires. Stored on this device only.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textSecondary
    )
    Spacer(Modifier.height(Space.m))

    if (!smsPerm.allPermissionsGranted) {
        InfoBanner(
            text = "SMS permission is required to send SOS alerts.",
            icon = Icons.Rounded.Sms,
            tint = colors.bandRisk,
            action = {
                GhostButton("Allow", { smsPerm.launchMultiplePermissionRequest() }, color = colors.bandRisk)
            }
        )
        Spacer(Modifier.height(Space.s))
    }
    if (!locPerm.status.isGranted) {
        InfoBanner(
            text = "Location sharpens the coordinates sent with an SOS.",
            icon = Icons.Rounded.LocationOn,
            tint = colors.bandMonitor,
            action = {
                GhostButton("Allow", { locPerm.launchPermissionRequest() }, color = colors.bandMonitor)
            }
        )
        Spacer(Modifier.height(Space.s))
    }

    if (state.emergencyContacts.isEmpty()) {
        BipssCard {
            Text(
                "No contacts yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }
    } else {
        state.emergencyContacts.forEach { contact ->
            ContactRow(contact) { vm.onDeleteContact(contact) }
            Spacer(Modifier.height(Space.s))
        }
    }

    Spacer(Modifier.height(Space.s))
    SecondaryButton(
        text = if (state.emergencyContacts.size >= MAX_EMERGENCY_CONTACTS)
            "Maximum of $MAX_EMERGENCY_CONTACTS reached"
        else "Add from contacts",
        leadingIcon = Icons.Rounded.PersonAdd,
        onClick = { navController.navigate(Routes.SETTINGS_CONTACT_PICKER) },
        enabled = state.emergencyContacts.size < MAX_EMERGENCY_CONTACTS,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ContactRow(contact: EmergencyContactEntity, onDelete: () -> Unit) {
    val colors = BipssTheme.colors
    BipssCard(contentPadding = PaddingValues(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Close, "Remove", tint = colors.textTertiary)
            }
        }
    }
}

// ── About ───────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard() {
    val colors = BipssTheme.colors
    BipssCard {
        InfoRow("Version", BuildConfig.VERSION_NAME)
        HairlineDivider()
        Spacer(Modifier.height(Space.m))
        Text(
            "BIPSS analyses your health entirely on-device. Risk scores come from a deterministic engine that keeps working with no model installed and no network available.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────────

@Composable
private fun DeleteDataDialog(state: SettingsUiState, vm: SettingsViewModel) {
    val colors = BipssTheme.colors
    AlertDialog(
        onDismissRequest = { vm.dismissDeleteDialog() },
        containerColor = colors.card,
        title = { Text("Delete my data", color = colors.textPrimary) },
        text = {
            Text(
                if (state.cloudBackupEnabled)
                    "Local data is cleared immediately and cloud data enters a 7-day deletion grace period. Insights pause while baselines rebuild."
                else
                    "This permanently deletes all local readings, baselines and settings. Insights pause while baselines rebuild.",
                color = colors.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.onDeleteDataConfirmed() }) {
                Text("Delete", color = colors.bandRisk)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissDeleteDialog() }) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

@Composable
private fun ForgetDeviceDialog(vm: SettingsViewModel) {
    val colors = BipssTheme.colors
    AlertDialog(
        onDismissRequest = { vm.dismissForgetDialog() },
        containerColor = colors.card,
        title = { Text("Forget device?", color = colors.textPrimary) },
        text = {
            Text(
                "The band will be disconnected and its pairing removed. Stored readings are kept.",
                color = colors.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.onForgetDeviceConfirmed() }) {
                Text("Forget", color = colors.bandRisk)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissForgetDialog() }) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}
