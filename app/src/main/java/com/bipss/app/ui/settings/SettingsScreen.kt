package com.bipss.app.ui.settings

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
                title = { Text(AppText.text(R.string.copy_74a883a037bc, "Settings"), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, AppText.text(R.string.copy_76900f1bfd16, "Back"), tint = colors.textSecondary)
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
            Group(AppText.text(R.string.copy_d696a35bdd18, "Profile")) { ProfileCard(state, navController) }
            Group(AppText.text(R.string.copy_20e5a3c21307, "My device")) { DeviceCard(state, vm, navController) }
            Group(AppText.text(R.string.copy_4d2e74abb828, "AI model")) { AiModelCard(state, vm) }
            Group(AppText.text(R.string.copy_3907fa7f8072, "Appearance")) { ThemeSelector(state.themePreference, vm::onThemePreferenceSelected) }
            Group(AppText.text(R.string.copy_4391d8a99171, "Privacy & data")) { PrivacySection(state, vm) }
            // Opened from the Home SOS warning: land on the section that fixes it.
            val emergency = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            LaunchedEffect(openAtEmergency) { if (openAtEmergency) emergency.bringIntoView() }
            Box(Modifier.bringIntoViewRequester(emergency)) {
                Column { Group(AppText.text(R.string.copy_9463d00b353e, "Emergency contacts")) { EmergencyContacts(state, vm, navController) } }
            }
            Group(AppText.text(R.string.copy_a36877416ca2, "Emergency support")) { NearbyHospitalsCard() }
            Group(AppText.text(R.string.copy_4efca0d10c5f, "About")) { AboutCard() }
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
            InfoRow(AppText.text(R.string.copy_dcd1d5223f73, "Name"), state.userName)
            HairlineDivider()
            InfoRow(AppText.text(R.string.copy_39b7370f30a3, "Age"), AppText.text(R.string.copy_c7233823363a, "%1\$s years", state.userAge))
            HairlineDivider()
            InfoRow(AppText.text(R.string.copy_bfe2eca40179, "Height"), AppText.text(R.string.copy_6303e3d69f3e, "%1\$s cm", state.userHeightCm.toInt()))
            HairlineDivider()
            InfoRow(AppText.text(R.string.copy_81d27ef6d503, "Weight"), AppText.text(R.string.copy_a289dfb01422, "%1\$s kg", state.userWeightKg))
            HairlineDivider()
            InfoRow(AppText.text(R.string.copy_a04630ef8bc3, "Gender"), state.userGender?.label ?: AppText.text(R.string.copy_4895f73177ab, "Not set"))
        } else {
            Text(
                AppText.text(R.string.copy_9107bb28a00e, "No profile set up yet."),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }
        Spacer(Modifier.height(Space.l))
        SecondaryButton(
            text = AppText.text(R.string.copy_15c4aa13037e, "Edit profile"),
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
                    if (state.bandSimulated) AppText.text(R.string.copy_2f7752a32492, "Simulated band") else state.bandName ?: AppText.text(R.string.copy_8e7dc7099b74, "No device paired"),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = if (state.isBandConnected) colors.bandNormal else colors.textTertiary
                    Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            state.isBandConnected -> AppText.text(R.string.copy_22965568d22a, "Connected")
                            state.bandMac != null -> AppText.text(R.string.copy_04dfac3671b4, "Disconnected")
                            else                  -> AppText.text(R.string.copy_53f15ea2f234, "Not paired")
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
                    AppText.text(R.string.copy_6578ef959318, "This band runs firmware v1, and this phone cuts its readings short, so none can be decoded. ") +
                        AppText.text(R.string.copy_542bb42c2ec6, "Updating the band to firmware v2 fixes this.")
                else
                    AppText.text(R.string.copy_31bad494e890, "This band runs firmware v1, which sends no movement or pulse-waveform data. ") +
                        AppText.text(R.string.copy_1c930633a5e8, "Sleep detection and exertion-based strain need firmware v2."),
                icon = Icons.Rounded.Info,
                tint = colors.bandMonitor
            )
        }
        Spacer(Modifier.height(Space.l))
        if (state.bandMac != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!state.isBandConnected) {
                    SecondaryButton(AppText.text(R.string.copy_bf8a9eab9e7e, "Reconnect"), { vm.onReconnectTap() }, Modifier.weight(1f))
                }
                SecondaryButton(
                    AppText.text(R.string.copy_0731e9dcc4b9, "Re-pair"),
                    { navController.navigate(Routes.ONBOARDING_CONNECT_BAND_REPAIR) },
                    Modifier.weight(1f)
                )
                SecondaryButton(
                    AppText.text(R.string.copy_a6bd489d9550, "Forget"), { vm.showForgetDialog() }, Modifier.weight(1f),
                    contentColor = colors.bandRisk
                )
            }
        } else {
            PrimaryButton(
                text = AppText.text(R.string.copy_419d18afe2d3, "Add device"),
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
        if (state.modelVersion != null && !state.modelLoadFailed &&
            (state.downloadPhase is DownloadProgress.Idle || state.downloadPhase is DownloadProgress.Success || state.downloadPhase is DownloadProgress.AlreadyUpToDate)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        AppText.text(R.string.copy_066574012791, "Model installed"),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        AppText.text(R.string.copy_b65bfa7f1eb0, "Version %1\$s", state.modelVersion) + (state.modelSizeMb?.let { AppText.text(R.string.copy_5e41af40c7dd, " · %1\$s MB", it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SecondaryButton(
                    text = if (state.isCheckingUpdate) AppText.text(R.string.copy_ec963ffc911b, "Checking…") else AppText.text(R.string.copy_f26f3275e735, "Check for updates"),
                    onClick = { vm.onCheckForUpdates() },
                    enabled = !state.isCheckingUpdate,
                    modifier = Modifier.weight(1f)
                )
                if (state.updateAvailable) {
                    SecondaryButton(
                        AppText.text(R.string.copy_c1c1009d3f37, "Update"), { vm.onDownloadModelTap() }, Modifier.weight(1f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }
            }
            state.modelUpdateMessage?.let { Text(it, Modifier.padding(top = Space.s), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        } else {
            Text(AppText.text(R.string.copy_2d714ba3591c, "Add optional AI conversations to Coach. Download only when you're ready."),
                style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
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
                        AppText.text(R.string.copy_6df01a33ec86, "%1\$s%  ·  %2\$s/%3\$s MB", (p.fraction * 100).toInt(), p.mbDownloaded, p.totalMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
                is DownloadProgress.Verifying, is DownloadProgress.Preparing -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.raised
                    )
                    Spacer(Modifier.height(Space.s))
                    Text(if (p is DownloadProgress.Preparing) AppText.text(R.string.copy_7800094e7601, "Preparing Coach…") else AppText.text(R.string.copy_15e433ec884f, "Checking your download…"), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
                is DownloadProgress.Initializing -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.raised
                    )
                    Spacer(Modifier.height(Space.s))
                    Text(AppText.text(R.string.copy_2bcefb166bf3, "Checking the model server…"), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
                else -> {
                    if (p is DownloadProgress.Failure) {
                        Text(p.reason, style = MaterialTheme.typography.bodySmall, color = colors.bandRisk)
                        Spacer(Modifier.height(Space.s))
                    }
                    PrimaryButton(if (p is DownloadProgress.Failure) AppText.text(R.string.copy_d8b8392e2c54, "Try again") else AppText.text(R.string.copy_8f2fd8eea02e, "Download AI model"), { vm.onDownloadModelTap() })
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
    // Google's consent screen for Drive; allowing it repeats the action that asked.
    lateinit var askConsent: (android.app.PendingIntent) -> Unit
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onDriveConsentResult(result.resultCode == android.app.Activity.RESULT_OK) { askConsent(it) } }
    askConsent = { pi -> consentLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi).build()) }

    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppText.text(R.string.copy_cd9a62e6a1af, "Cloud backup"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.height(Space.xs))
                Text(
                    AppText.text(R.string.copy_ccfb9e3e8962, "Your own copy in your Google Drive (a private app folder only BIPSS can read): profile, ") +
                        AppText.text(R.string.copy_e03f67185f51, "settings, emergency contacts, baselines, water log and cycle log. Raw sensor readings stay ") +
                        AppText.text(R.string.copy_78544e6b291d, "on this phone. For moving to a new phone - nothing reaches the developer."),
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
        Spacer(Modifier.height(Space.m))
        SecondaryButton(text = if (state.isConnectingDrive) AppText.text(R.string.copy_72021eb70e91, "Connecting…") else AppText.text(R.string.copy_4406cf53eddd, "Connect Google Drive"),
            onClick = { vm.onConnectDriveTap(askConsent) },
            enabled = !state.isConnectingDrive && !state.isBackingUp && !state.isRestoring,
            modifier = Modifier.fillMaxWidth())
        state.backupStatusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.s)) }
        if (state.cloudBackupEnabled) {
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SecondaryButton(
                    text = if (state.isBackingUp) AppText.text(R.string.copy_bc51147d52ab, "Backing up…") else AppText.text(R.string.copy_02a2840b598b, "Back up now"),
                    onClick = { vm.onBackupNowTap(askConsent) },
                    enabled = !state.isBackingUp && !state.isRestoring && !state.isConnectingDrive,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = if (state.isRestoring) AppText.text(R.string.copy_5a4918e0201c, "Restoring…") else AppText.text(R.string.copy_a76e13b98392, "Restore"),
                    onClick = { vm.onRestoreTap(askConsent) },
                    enabled = !state.isBackingUp && !state.isRestoring && !state.isConnectingDrive,
                    modifier = Modifier.weight(1f)
                )
            }
            val fmt = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
            val times = listOfNotNull(
                state.lastBackupAtMs?.let { AppText.text(R.string.copy_7a0b372dd6d0, "Last backup: %1\$s", fmt.format(java.util.Date(it))) },
                state.lastRestoreAtMs?.let { AppText.text(R.string.copy_f1e226aed40a, "Last restore: %1\$s", fmt.format(java.util.Date(it))) }
            )
            if (times.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                Text(times.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
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
                    AppText.text(R.string.copy_3b1d9328b4ae, "Share data with BIPSS"),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    AppText.text(R.string.copy_28ab74a9e51a, "Optional, to help improve BIPSS: shares minute averages of your band readings and your ") +
                        AppText.text(R.string.copy_ab79bbd7f023, "sleep summaries, under an anonymous ID, every hour. Not a backup - nothing is ever ") +
                        AppText.text(R.string.copy_f3ff24765adf, "restored from it. Turning it off stops new uploads; Delete my data also removes ") +
                        AppText.text(R.string.copy_ef9906a344c9, "what was shared."),
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
                AppText.text(R.string.copy_c0dc038399f9, "Your sharing ID: %1\$s", state.sharingUsername),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
        }
    }

    Spacer(Modifier.height(Space.m))
    SecondaryButton(
        text = AppText.text(R.string.copy_5c4653236b30, "Delete my data"),
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
        AppText.text(R.string.copy_d2e7ea72463e, "Contacts here receive an SMS when SOS fires, with your location if BIPSS can get it. Nearby hospitals that list a mobile number are texted too, with these contacts' names and numbers. Stored on this phone, and in your Drive backup if it's on."),
        style = MaterialTheme.typography.bodySmall,
        color = colors.textSecondary
    )
    Spacer(Modifier.height(Space.m))

    if (!smsPerm.allPermissionsGranted) {
        InfoBanner(
            text = AppText.text(R.string.copy_35a7368be5e3, "SMS permission is required to send SOS alerts."),
            icon = Icons.Rounded.Sms,
            tint = colors.bandRisk,
            action = {
                GhostButton(AppText.text(R.string.copy_e213c161d5ce, "Allow"), { smsPerm.launchMultiplePermissionRequest() }, color = colors.bandRisk)
            }
        )
        Spacer(Modifier.height(Space.s))
    }
    if (!locPerm.isGranted) {
        InfoBanner(
            text = AppText.text(R.string.copy_38d1138d7442, "Location sharpens the coordinates sent with an SOS."),
            icon = Icons.Rounded.LocationOn,
            tint = colors.bandMonitor,
            action = {
                GhostButton(AppText.text(R.string.copy_e213c161d5ce, "Allow"), { locPerm.request() }, color = colors.bandMonitor)
            }
        )
        Spacer(Modifier.height(Space.s))
    }

    if (state.emergencyContacts.isEmpty()) {
        BipssCard {
            Text(
                AppText.text(R.string.copy_645a0fef7c6e, "No contacts yet."),
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
            AppText.text(R.string.copy_0409f1dc6f8a, "Maximum of %1\$s reached", MAX_EMERGENCY_CONTACTS)
        else AppText.text(R.string.copy_b4fb13e60102, "Add from contacts"),
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
                Spacer(Modifier.height(Space.xs))
                Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Close, AppText.text(R.string.copy_c3812fc4acb8, "Remove"), tint = colors.textTertiary)
            }
        }
    }
}

// ── About ───────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard() {
    val colors = BipssTheme.colors
    BipssCard {
        InfoRow(AppText.text(R.string.copy_dd167905de0d, "Version"), BuildConfig.VERSION_NAME)
        HairlineDivider()
        Spacer(Modifier.height(Space.m))
        Text(
            AppText.text(R.string.copy_8a950d18f78a, "BIPSS analyses your health entirely on-device. Risk scores come from a deterministic engine that keeps working with no model installed and no network available."),
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
        AppText.text(R.string.copy_b947763ae33e, "Deleting your shared data is queued. It will finish automatically once you're online.")
    com.bipss.app.data.sharing.CloudDeletionStatus.IN_PROGRESS ->
        AppText.text(R.string.copy_77a1760b344b, "Deleting your shared data… %1\$s record(s) removed so far.", state.cloudDeletionCount)
    com.bipss.app.data.sharing.CloudDeletionStatus.DONE ->
        if (state.cloudAccountRemoved)
            AppText.text(R.string.copy_0ab943e7c988, "Your shared data was deleted (%1\$s record(s)), and its anonymous sharing ID removed.", state.cloudDeletionCount)
        else
            AppText.text(R.string.copy_194dc346a152, "Your shared data was deleted (%1\$s record(s)). Its anonymous sharing ID could not be ", state.cloudDeletionCount) +
                AppText.text(R.string.copy_cf3e48be4bae, "removed, but this phone is signed out of it, so sharing again starts a new ID.")
    com.bipss.app.data.sharing.CloudDeletionStatus.FAILED ->
        AppText.text(R.string.copy_3dd052801d4f, "Deleting your shared data failed after several attempts. Try Delete my data again while online.")
    com.bipss.app.data.sharing.CloudDeletionStatus.IDENTITY_LOST ->
        AppText.text(R.string.copy_f447cd4d3f34, "Your shared data could not be deleted from this phone: the anonymous sharing ID it was stored under is no longer signed in here.")
}

@Composable
private fun DeleteDataDialog(vm: SettingsViewModel) {
    val colors = BipssTheme.colors
    AlertDialog(
        onDismissRequest = { vm.dismissDeleteDialog() },
        containerColor = colors.card,
        title = { Text(AppText.text(R.string.copy_5c4653236b30, "Delete my data"), color = colors.textPrimary) },
        text = {
            Text(
                AppText.text(R.string.copy_e5eb30060d40, "This deletes, from this phone and now: every reading, baseline, sleep record, water log, ") +
                    AppText.text(R.string.copy_09900759343b, "emergency contact, SOS log, your profile and your settings. If you have shared data with ") +
                    AppText.text(R.string.copy_1b0138d37770, "BIPSS, that is deleted too - immediately if you are online, otherwise automatically once you ") +
                    AppText.text(R.string.copy_57242fc3a776, "are. Not touched: your theme, the downloaded AI model file, any backup in your own Google ") +
                    AppText.text(R.string.copy_b4a663e61bda, "Drive, and height or weight already saved to Health Connect."),
                color = colors.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.onDeleteDataConfirmed() }) {
                Text(AppText.text(R.string.copy_e2d0a54968ea, "Delete"), color = colors.bandRisk)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissDeleteDialog() }) {
                Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel"), color = colors.textSecondary)
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
        title = { Text(AppText.text(R.string.copy_ee5ef73aae18, "Your data"), color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    if (result.localDeleted)
                        AppText.text(R.string.copy_5c6199f826a9, "✓ Your readings, history, contacts, profile and settings have been deleted from this phone.")
                    else AppText.text(R.string.copy_ab45f739e9f9, "Deleting data on this phone…"),
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (!result.hadSharedData) AppText.text(R.string.copy_a267427dc92d, "You never shared data with BIPSS, so there was nothing in the cloud to delete.")
                    else cloudDeletionLine(state) ?: AppText.text(R.string.copy_06d1f5b85aaa, "Deleting your shared data is queued."),
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.onDeletionAcknowledged() }) {
                Text(AppText.text(R.string.copy_11a6767d5674, "Done"), color = MaterialTheme.colorScheme.primary)
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
        title = { Text(AppText.text(R.string.copy_7e1e65d6dfa3, "Forget device?"), color = colors.textPrimary) },
        text = {
            Text(
                AppText.text(R.string.copy_f5ffb03f241f, "The band will be disconnected and its pairing removed. Stored readings are kept."),
                color = colors.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.onForgetDeviceConfirmed() }) {
                Text(AppText.text(R.string.copy_a6bd489d9550, "Forget"), color = colors.bandRisk)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissForgetDialog() }) {
                Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel"), color = colors.textSecondary)
            }
        }
    )
}
