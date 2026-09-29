package com.bipss.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
    openAtEmergency: Boolean = false,
    vm: SettingsViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    if (state.showDeleteLocalDataDialog) DeleteDataDialog(vm)
    state.deletionResult?.let { DeletionResultDialog(it, state, vm) }
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
            // Opened from the Home SOS warning: land on the section that fixes it.
            val emergency = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            LaunchedEffect(openAtEmergency) { if (openAtEmergency) emergency.bringIntoView() }
            Box(Modifier.bringIntoViewRequester(emergency)) {
                Column { Group("Emergency contacts") { EmergencyContacts(state, vm, navController) } }
            }
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
            HairlineDivider()
            InfoRow("Gender", state.userGender?.label ?: "Not set")
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
                    if (state.bandSimulated) "Simulated band" else state.bandName ?: "No device paired",
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
        if (state.isBandConnected && state.bandProtocol == com.bipss.app.ble.BandProtocolVersion.V1_LEGACY) {
            Spacer(Modifier.height(Space.m))
            InfoBanner(
                text = if (state.legacyFramesTruncated)
                    "This band runs firmware v1, and this phone cuts its readings short, so none can be decoded. " +
                        "Updating the band to firmware v2 fixes this."
                else
                    "This band runs firmware v1, which sends no movement or pulse-waveform data. " +
                        "Sleep detection and exertion-based strain need firmware v2.",
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor
            )
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
                        "Version ${state.modelVersion}" + (state.modelSizeMb?.let { " · $it MB" } ?: ""),
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
        } else if (!com.bipss.app.ai.isModelDownloadConfigured) {
            Text(
                "No on-device model is available for this version of BIPSS yet. The Coach tab uses rule-based replies, and every other feature works normally.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        } else {
            Text(
                "The on-device model isn't installed. The Coach tab uses rule-based replies, and every other feature works normally.",
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
                is DownloadProgress.Initializing -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.raised
                    )
                    Spacer(Modifier.height(Space.s))
                    Text("Checking the model server…", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
                else -> {
                    if (p is DownloadProgress.Failure) {
                        Text(p.reason, style = MaterialTheme.typography.bodySmall, color = colors.bandRisk)
                        Spacer(Modifier.height(Space.s))
                    }
                    PrimaryButton(if (p is DownloadProgress.Failure) "Try again" else "Download AI model", { vm.onDownloadModelTap() })
                }
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

    // The real, persisted status of the last shared-data deletion.
    cloudDeletionLine(state)?.let { line ->
        Spacer(Modifier.height(Space.m))
        InfoBanner(
            text = line,
            icon = if (state.cloudDeletionStatus == com.bipss.app.data.sharing.CloudDeletionStatus.DONE)
                Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
            tint = when (state.cloudDeletionStatus) {
                com.bipss.app.data.sharing.CloudDeletionStatus.DONE -> colors.bandNormal
                com.bipss.app.data.sharing.CloudDeletionStatus.FAILED,
                com.bipss.app.data.sharing.CloudDeletionStatus.IDENTITY_LOST -> colors.bandRisk
                else -> colors.bandMonitor
            }
        )
    }

    Spacer(Modifier.height(Space.m))

    // ── Data sharing (separate from cloud backup) ────────────────────────
    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Share data with BIPSS",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Text(
                    "Sends hourly health summaries to help improve the app. " +
                        "This is not a backup — it does not restore your data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Spacer(Modifier.width(Space.m))
            Switch(
                checked = state.dataSharingEnabled,
                onCheckedChange = { vm.onDataSharingToggle(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
        if (state.dataSharingEnabled && state.sharingUsername != null) {
            Spacer(Modifier.height(Space.s))
            Text(
                "Your sharing ID: ${state.sharingUsername}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
        }
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
    val locPerm = rememberLocationPermission()

    Text(
        "Contacts here receive an SMS when SOS fires, with your location if BIPSS can get it. Stored on this phone, and in your Drive backup if it's on.",
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
    if (!locPerm.isGranted) {
        InfoBanner(
            text = "Location sharpens the coordinates sent with an SOS.",
            icon = Icons.Rounded.LocationOn,
            tint = colors.bandMonitor,
            action = {
                GhostButton("Allow", { locPerm.request() }, color = colors.bandMonitor)
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

/** One honest sentence about the shared-data deletion, or null when there is nothing to report. */
private fun cloudDeletionLine(state: SettingsUiState): String? = when (state.cloudDeletionStatus) {
    com.bipss.app.data.sharing.CloudDeletionStatus.NONE -> null
    com.bipss.app.data.sharing.CloudDeletionStatus.PENDING ->
        "Deleting your shared data is queued. It will finish automatically once you're online."
    com.bipss.app.data.sharing.CloudDeletionStatus.IN_PROGRESS ->
        "Deleting your shared data… ${state.cloudDeletionCount} record(s) removed so far."
    com.bipss.app.data.sharing.CloudDeletionStatus.DONE ->
        if (state.cloudAccountRemoved)
            "Your shared data was deleted (${state.cloudDeletionCount} record(s)), and its anonymous sharing ID removed."
        else
            "Your shared data was deleted (${state.cloudDeletionCount} record(s)). Its anonymous sharing ID could not be " +
                "removed, but this phone is signed out of it, so sharing again starts a new ID."
    com.bipss.app.data.sharing.CloudDeletionStatus.FAILED ->
        "Deleting your shared data failed after several attempts. Try Delete my data again while online."
    com.bipss.app.data.sharing.CloudDeletionStatus.IDENTITY_LOST ->
        "Your shared data could not be deleted from this phone: the anonymous sharing ID it was stored under is no longer signed in here."
}

@Composable
private fun DeleteDataDialog(vm: SettingsViewModel) {
    val colors = BipssTheme.colors
    AlertDialog(
        onDismissRequest = { vm.dismissDeleteDialog() },
        containerColor = colors.card,
        title = { Text("Delete my data", color = colors.textPrimary) },
        text = {
            Text(
                "This deletes, from this phone and now: every reading, baseline, sleep record, water log, " +
                    "emergency contact, SOS log, your profile and your settings. If you have shared data with " +
                    "BIPSS, that is deleted too - immediately if you are online, otherwise automatically once you " +
                    "are. Not touched: your theme, the downloaded AI model file, any backup in your own Google " +
                    "Drive, and height or weight already saved to Health Connect.",
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

/** What actually happened, line by line. The shared-data line updates live from the worker's status. */
@Composable
private fun DeletionResultDialog(result: DeletionResult, state: SettingsUiState, vm: SettingsViewModel) {
    val colors = BipssTheme.colors
    AlertDialog(
        onDismissRequest = {},
        containerColor = colors.card,
        title = { Text("Your data", color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    if (result.localDeleted)
                        "✓ Your readings, history, contacts, profile and settings have been deleted from this phone."
                    else "Deleting data on this phone…",
                    color = colors.textPrimary
                )
                Text(
                    if (!result.hadSharedData) "You never shared data with BIPSS, so there was nothing in the cloud to delete."
                    else cloudDeletionLine(state) ?: "Deleting your shared data is queued.",
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.onDeletionAcknowledged() }) {
                Text("Done", color = MaterialTheme.colorScheme.primary)
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
