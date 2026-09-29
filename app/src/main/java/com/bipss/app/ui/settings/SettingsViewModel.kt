package com.bipss.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ai.ChatbotEngine
import com.bipss.app.ai.DownloadProgress
import com.bipss.app.ai.ModelDownloadManager
import com.bipss.app.ble.BleConnectionState
import com.bipss.app.ble.BleGattManager
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.backup.BackupResult
import com.bipss.app.data.backup.DriveBackupManager
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.dao.EmergencyContactDao
import com.bipss.app.data.db.entity.EmergencyContactEntity
import com.bipss.app.ui.theme.ThemePreference
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val userName:       String  = "",
    val userAge:        Int     = 0,
    val userHeightCm:   Float   = 0f,
    val userWeightKg:   Float   = 0f,
    val userGender:     com.bipss.app.data.Gender? = null,

    val bandName:        String? = null,
    val bandMac:         String? = null,
    val isBandConnected: Boolean = false,
    /** Wire protocol of the connected band; null while none is connected. */
    val bandProtocol: com.bipss.app.ble.BandProtocolVersion? = null,
    val bandSimulated: Boolean = false,
    val legacyFramesTruncated: Boolean = false,

    val modelVersion:    String? = null,
    val modelFilePath:   String? = null,
    /** From the manifest actually fetched at install time — null until known. */
    val modelSizeMb:     Int?    = null,
    val isCheckingUpdate: Boolean = false,
    val updateAvailable: Boolean = false,
    val downloadPhase:   DownloadProgress = DownloadProgress.Idle,

    val cloudBackupEnabled:    Boolean = false,
    val isBackingUp:           Boolean = false,
    val isRestoring:           Boolean = false,
    val backupStatusMessage:   String? = null,

    val emergencyContacts: List<EmergencyContactEntity> = emptyList(),

    val themePreference: ThemePreference = ThemePreference.DARK,

    val dataSharingEnabled: Boolean = false,
    val sharingUsername:    String? = null,

    val showDeleteLocalDataDialog: Boolean = false,
    val showForgetDeviceDialog:    Boolean = false,

    /** Non-null while the "Delete my data" result is on screen. */
    val deletionResult: DeletionResult? = null,
    /** Outcome of the most recent shared-data deletion, shown in Privacy afterwards. */
    val cloudDeletionStatus: com.bipss.app.data.sharing.CloudDeletionStatus = com.bipss.app.data.sharing.CloudDeletionStatus.NONE,
    val cloudDeletionCount:  Int = 0,
    val cloudAccountRemoved: Boolean = false
)

/** What "Delete my data" actually did. Every line on the result screen comes from here. */
data class DeletionResult(
    val localDeleted:    Boolean,
    /** True when there was shared (Firebase) data to delete at all. */
    val hadSharedData:   Boolean
)

class SettingsViewModel(
    private val prefsRepo:            UserPreferencesRepository,
    private val bleGattManager:       BleGattManager,
    private val modelDownloadManager: ModelDownloadManager,
    private val emergencyContactDao:  EmergencyContactDao,
    private val chatbotEngine:        ChatbotEngine,
    private val db:                   AppDatabase,
    val driveBackupManager:           DriveBackupManager,
    private val appContext:           Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Separate StateFlow consumed by MainActivity for BipssTheme */
    val themePreference: StateFlow<ThemePreference> =
        prefsRepo.themePreference.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreference.DARK
        )

    init {
        observeAll()
    }

    private fun observeAll() {
        viewModelScope.launch {
            combine(
                prefsRepo.userProfile,
                prefsRepo.pairedBandMac,
                prefsRepo.pairedBandName,
                bleGattManager.connectionState,
                prefsRepo.installedModelVersion
            ) { profile, mac, bandName, connState, modelVersion ->
                listOf(profile, mac, bandName, connState, modelVersion)
            }.collect { list ->
                val profile    = list[0] as? com.bipss.app.data.UserProfile
                val mac        = list[1] as? String
                val bandName   = list[2] as? String
                val connState  = list[3] as BleConnectionState
                val modelVer   = list[4] as? String
                _uiState.update { s ->
                    s.copy(
                        userName        = profile?.name ?: "",
                        userAge         = profile?.age  ?: 0,
                        userHeightCm    = profile?.heightCm ?: 0f,
                        userWeightKg    = profile?.weightKg ?: 0f,
                        userGender      = profile?.gender,
                        bandMac         = mac,
                        bandName        = bandName,
                        isBandConnected = connState is BleConnectionState.Connected,
                        modelVersion    = modelVer
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                bleGattManager.protocolVersion, bleGattManager.isSimulated, bleGattManager.legacyFramesTruncated
            ) { v, sim, truncated -> Triple(v, sim, truncated) }
                .collect { (v, sim, truncated) ->
                    _uiState.update { it.copy(bandProtocol = v, bandSimulated = sim, legacyFramesTruncated = truncated) }
                }
        }

        viewModelScope.launch {
            combine(prefsRepo.cloudBackupEnabled, prefsRepo.themePreference) { backup, theme -> backup to theme }
                .collect { (backup, theme) ->
                    _uiState.update { it.copy(cloudBackupEnabled = backup, themePreference = theme) }
                }
        }

        viewModelScope.launch {
            combine(
                prefsRepo.cloudDeletionStatus, prefsRepo.cloudDeletionCount, prefsRepo.cloudDeletionAccountRemoved
            ) { status, count, removed -> Triple(status, count, removed) }
                .collect { (status, count, removed) ->
                    _uiState.update {
                        it.copy(cloudDeletionStatus = status, cloudDeletionCount = count, cloudAccountRemoved = removed)
                    }
                }
        }

        viewModelScope.launch {
            emergencyContactDao.all().collect { contacts ->
                _uiState.update { it.copy(emergencyContacts = contacts) }
            }
        }

        viewModelScope.launch {
            prefsRepo.installedModelSizeBytes.collect { bytes ->
                _uiState.update { it.copy(modelSizeMb = bytes?.let { b -> (b / 1_048_576L).toInt() }) }
            }
        }

        viewModelScope.launch {
            combine(
                prefsRepo.dataSharingEnabled,
                prefsRepo.sharingUsername
            ) { enabled, username ->
                Pair(enabled, username)
            }.collect { (enabled, username) ->
                _uiState.update {
                    it.copy(dataSharingEnabled = enabled, sharingUsername = username)
                }
            }
        }
    }

    // ── Device ────────────────────────────────────────────────────────────────
    fun onReconnectTap() {
        viewModelScope.launch {
            val mac = _uiState.value.bandMac ?: return@launch
            bleGattManager.connectToDevice(mac)
        }
    }

    fun showForgetDialog()    { _uiState.update { it.copy(showForgetDeviceDialog = true)  } }
    fun dismissForgetDialog() { _uiState.update { it.copy(showForgetDeviceDialog = false) } }

    fun onForgetDeviceConfirmed() {
        viewModelScope.launch {
            bleGattManager.disconnect()
            prefsRepo.clearPairedBand()
            _uiState.update { it.copy(showForgetDeviceDialog = false) }
        }
    }

    // ── AI Model ─────────────────────────────────────────────────────────────
    fun onCheckForUpdates() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true) }
            try {
                val manifest = modelDownloadManager.fetchManifestPublic()
                val installed = prefsRepo.installedModelVersion.first()
                _uiState.update {
                    it.copy(isCheckingUpdate = false,
                            updateAvailable = manifest != null && manifest.version != installed)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCheckingUpdate = false) }
            }
        }
    }

    fun onDownloadModelTap() {
        viewModelScope.launch {
            modelDownloadManager.downloadModelIfNeeded().collect { progress ->
                _uiState.update { it.copy(downloadPhase = progress) }
                if (progress is DownloadProgress.Success) {
                    val path = prefsRepo.installedModelFilePath.first()
                    if (path != null) chatbotEngine.initialize(path)
                }
            }
        }
    }

    // ── Privacy ───────────────────────────────────────────────────────────────
    fun onCloudBackupToggle(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setCloudBackupEnabled(enabled) }
    }

    fun onDataSharingToggle(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setDataSharingEnabled(enabled)
            if (enabled) prefsRepo.getOrCreateSharingUsername()
        }
    }

    /** Returns a sign-in Intent to launch if the caller needs to (re)authorize Drive access. */
    fun onBackupNowTap(onNeedsSignIn: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBackingUp = true, backupStatusMessage = null) }
            when (val result = driveBackupManager.backupNow()) {
                is BackupResult.Success -> _uiState.update {
                    it.copy(isBackingUp = false, backupStatusMessage = "Backed up to Google Drive.")
                }
                is BackupResult.NotSignedIn -> {
                    _uiState.update { it.copy(isBackingUp = false) }
                    onNeedsSignIn(result.signInIntent)
                }
                is BackupResult.Failure -> _uiState.update {
                    it.copy(isBackingUp = false, backupStatusMessage = "Backup failed: ${result.message}")
                }
            }
        }
    }

    fun onRestoreTap(onNeedsSignIn: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, backupStatusMessage = null) }
            when (val result = driveBackupManager.restoreLatest()) {
                is BackupResult.Success -> _uiState.update {
                    it.copy(isRestoring = false, backupStatusMessage = "Restored from Google Drive.")
                }
                is BackupResult.NotSignedIn -> {
                    _uiState.update { it.copy(isRestoring = false) }
                    onNeedsSignIn(result.signInIntent)
                }
                is BackupResult.Failure -> _uiState.update {
                    it.copy(isRestoring = false, backupStatusMessage = "Restore failed: ${result.message}")
                }
            }
        }
    }

    fun dismissBackupStatus() { _uiState.update { it.copy(backupStatusMessage = null) } }

    fun onDeleteDataTap() { _uiState.update { it.copy(showDeleteLocalDataDialog = true) } }
    fun dismissDeleteDialog() { _uiState.update { it.copy(showDeleteLocalDataDialog = false) } }

    /**
     * Deletes everything on this phone now, and everything shared with BIPSS
     * as soon as the phone is online.
     *
     * Collection is stopped first (band, background work) so nothing is
     * written back mid-wipe. The shared-data identity is captured before the
     * local wipe, handed to [com.bipss.app.data.sharing.CloudDeletionWorker],
     * and kept through the wipe so the cloud side can finish later. Nothing
     * here claims a deletion that has not happened: the result screen reads
     * the worker's real, persisted status.
     */
    fun onDeleteDataConfirmed() {
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteLocalDataDialog = false) }
            // A deletion still pending from an earlier wipe (offline, or failed)
            // is picked up again: the sharing uid itself was cleared by that wipe.
            val pendingUid = prefsRepo.pendingCloudDeletionUid.first()
            val sharedUid = prefsRepo.sharingFirebaseUid.first() ?: pendingUid

            bleGattManager.disconnect()
            com.bipss.app.ble.BleForegroundService.stop(appContext)
            // Stops anything mid-write (aggregation, sleep analysis, sync) so it
            // cannot put rows back while the tables are cleared. The periodic
            // jobs are rescheduled below; nothing restarts the process for us.
            androidx.work.WorkManager.getInstance(appContext).cancelAllWork()

            if (sharedUid != null) {
                val resuming = pendingUid == sharedUid
                prefsRepo.setPendingCloudDeletionUid(sharedUid)
                prefsRepo.setCloudDeletionStatus(
                    com.bipss.app.data.sharing.CloudDeletionStatus.PENDING,
                    if (resuming) prefsRepo.cloudDeletionCount.first() else 0
                )
            }
            // Room's clearAllTables() blocks; it must not run on the main thread.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.clearAllTables() }
            prefsRepo.clearLocalDataPrefs()
            if (sharedUid != null) {
                com.bipss.app.data.sharing.CloudDeletionWorker.enqueue(appContext, replace = true)
            }
            com.bipss.app.BipssApplication.schedulePeriodicWork(appContext)

            _uiState.update { it.copy(deletionResult = DeletionResult(localDeleted = true, hadSharedData = sharedUid != null)) }
        }
    }

    /** After the user has read the result: back to the start, since the profile is gone. */
    fun onDeletionAcknowledged() {
        viewModelScope.launch {
            _uiState.update { it.copy(deletionResult = null) }
            prefsRepo.resetOnboarding()
        }
    }

    // ── Emergency Contacts ───────────────────────────────────────────────────
    // Adding a contact now goes through ContactPickerScreen (reads the
    // device's own Contacts app) instead of a manual name/phone form.
    fun onDeleteContact(contact: EmergencyContactEntity) {
        viewModelScope.launch { emergencyContactDao.delete(contact) }
    }

    // ── Appearance ───────────────────────────────────────────────────────────
    fun onThemePreferenceSelected(pref: ThemePreference) {
        viewModelScope.launch { prefsRepo.setThemePreference(pref) }
    }
}
