package com.bipss.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import com.bipss.app.domain.environmental.HealthAdvisory
import com.bipss.app.domain.environmental.ImdAlert
import com.bipss.app.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Lenient on purpose: a cache that fails to decode must degrade to "no data", never crash. */
private val envJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Central DataStore repository. Accumulates keys from all plans (1–10).
 * All keys are private; access is through typed Flow properties and suspend setters.
 */
class UserPreferencesRepository(private val context: Context) {

    private val dataStore = context.dataStore

    // ── Plan 1: Onboarding gate ───────────────────────────────────────────────
    private val KEY_ONBOARDING_DONE   = booleanPreferencesKey("has_completed_onboarding")

    val hasCompletedOnboarding: Flow<Boolean> =
        dataStore.data.map { it[KEY_ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingComplete() {
        dataStore.edit { it[KEY_ONBOARDING_DONE] = true }
    }

    // ── Plan 2: User Profile ──────────────────────────────────────────────────
    private val KEY_PROFILE_NAME      = stringPreferencesKey("profile_name")
    private val KEY_PROFILE_AGE       = intPreferencesKey("profile_age")
    private val KEY_PROFILE_HEIGHT_CM = floatPreferencesKey("profile_height_cm")
    private val KEY_PROFILE_WEIGHT_KG = floatPreferencesKey("profile_weight_kg")

    val userProfile: Flow<UserProfile?> = dataStore.data.map { prefs ->
        val name = prefs[KEY_PROFILE_NAME] ?: return@map null
        UserProfile(
            name     = name,
            age      = prefs[KEY_PROFILE_AGE]       ?: 0,
            heightCm = prefs[KEY_PROFILE_HEIGHT_CM] ?: 0f,
            weightKg = prefs[KEY_PROFILE_WEIGHT_KG] ?: 0f
        )
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        dataStore.edit {
            it[KEY_PROFILE_NAME]      = profile.name
            it[KEY_PROFILE_AGE]       = profile.age
            it[KEY_PROFILE_HEIGHT_CM] = profile.heightCm
            it[KEY_PROFILE_WEIGHT_KG] = profile.weightKg
        }
    }

    // ── Plan 3: BLE band ─────────────────────────────────────────────────────
    private val KEY_PAIRED_BAND_MAC  = stringPreferencesKey("paired_band_mac")
    private val KEY_PAIRED_BAND_NAME = stringPreferencesKey("paired_band_name")

    val pairedBandMac:  Flow<String?> = dataStore.data.map { it[KEY_PAIRED_BAND_MAC] }
    val pairedBandName: Flow<String?> = dataStore.data.map { it[KEY_PAIRED_BAND_NAME] }

    suspend fun savePairedBand(mac: String, name: String) {
        dataStore.edit {
            it[KEY_PAIRED_BAND_MAC]  = mac
            it[KEY_PAIRED_BAND_NAME] = name
        }
    }

    suspend fun clearPairedBand() {
        dataStore.edit {
            it.remove(KEY_PAIRED_BAND_MAC)
            it.remove(KEY_PAIRED_BAND_NAME)
        }
    }

    // ── Plan 4: AI Model ─────────────────────────────────────────────────────
    private val KEY_MODEL_VERSION      = stringPreferencesKey("model_version")
    private val KEY_MODEL_FILE_PATH    = stringPreferencesKey("model_file_path")
    private val KEY_MODEL_SIZE_BYTES   = longPreferencesKey("model_size_bytes")
    private val KEY_CHATBOT_ENGINE_FAILED = booleanPreferencesKey("chatbot_engine_failed")

    val installedModelVersion:  Flow<String?> = dataStore.data.map { it[KEY_MODEL_VERSION] }
    val installedModelFilePath: Flow<String?> = dataStore.data.map { it[KEY_MODEL_FILE_PATH] }
    // Actual downloaded size from the manifest that was fetched at install
    // time — never a guessed/hardcoded number. Null until a model has
    // actually been downloaded by this build.
    val installedModelSizeBytes: Flow<Long?> = dataStore.data.map { it[KEY_MODEL_SIZE_BYTES] }
    val chatbotEngineFailedFlag: Flow<Boolean> = dataStore.data.map { it[KEY_CHATBOT_ENGINE_FAILED] ?: false }

    suspend fun saveInstalledModel(version: String, filePath: String, sizeBytes: Long) {
        dataStore.edit {
            it[KEY_MODEL_VERSION]    = version
            it[KEY_MODEL_FILE_PATH]  = filePath
            it[KEY_MODEL_SIZE_BYTES] = sizeBytes
        }
    }

    suspend fun setChatbotEngineFailed(failed: Boolean) {
        dataStore.edit { it[KEY_CHATBOT_ENGINE_FAILED] = failed }
    }

    // ── Water log (legacy) ───────────────────────────────────────────────────
    // Drinks now live in Room (see WaterRepository). These keys held a single
    // UTC-day counter and are read once to migrate, then removed.
    private val KEY_LAST_WATER_LOG_MS = longPreferencesKey("last_water_log_ms")
    private val KEY_WATER_LOG_COUNT   = intPreferencesKey("water_log_count_today")
    private val KEY_WATER_LOG_ML      = intPreferencesKey("water_log_ml_today")
    private val KEY_WATER_LOG_DAY     = longPreferencesKey("water_log_day_epoch")

    data class LegacyWaterCounter(val lastLogMs: Long, val count: Int, val totalMl: Int)

    /** Returns the old counter (if any) and deletes its keys, so it migrates exactly once. */
    suspend fun consumeLegacyWaterCounter(): LegacyWaterCounter? {
        var result: LegacyWaterCounter? = null
        dataStore.edit { prefs ->
            val last = prefs[KEY_LAST_WATER_LOG_MS]
            if (last != null) {
                result = LegacyWaterCounter(
                    lastLogMs = last,
                    count     = prefs[KEY_WATER_LOG_COUNT] ?: 0,
                    totalMl   = prefs[KEY_WATER_LOG_ML] ?: 0
                )
            }
            prefs.remove(KEY_LAST_WATER_LOG_MS)
            prefs.remove(KEY_WATER_LOG_COUNT)
            prefs.remove(KEY_WATER_LOG_ML)
            prefs.remove(KEY_WATER_LOG_DAY)
        }
        return result
    }

    // ── Baseline age ─────────────────────────────────────────────────────────
    // Drives the widening 3 -> 7 -> 15 -> 30 -> 90 day schedule. Stamped from
    // the first real band reading, never from install time, so a phone that sat
    // in a drawer for a week does not claim a week of baseline.
    private val KEY_FIRST_READING_MS = longPreferencesKey("first_reading_ms")

    val firstReadingMs: Flow<Long?> = dataStore.data.map { it[KEY_FIRST_READING_MS] }

    suspend fun recordFirstReadingIfNeeded(timestampMs: Long) {
        dataStore.edit { prefs ->
            if (prefs[KEY_FIRST_READING_MS] == null) prefs[KEY_FIRST_READING_MS] = timestampMs
        }
    }

    /** Whole days of collected data. Zero until the band has streamed something. */
    suspend fun daysOfData(): Int {
        val first = dataStore.data.map { it[KEY_FIRST_READING_MS] }.first() ?: return 0
        return ((System.currentTimeMillis() - first) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    // ── Alert notification de-duplication ────────────────────────────────────
    // Without this the 15-minute sync re-notifies the same warning all day.
    private val KEY_NOTIFIED_ALERT_IDS = stringSetPreferencesKey("notified_alert_ids")

    suspend fun hasNotifiedAlert(identifier: String): Boolean =
        dataStore.data.map { it[KEY_NOTIFIED_ALERT_IDS] ?: emptySet() }.first().contains(identifier)

    suspend fun markAlertNotified(identifier: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_NOTIFIED_ALERT_IDS] ?: emptySet()
            // Bounded so the set cannot grow without limit across a long install.
            prefs[KEY_NOTIFIED_ALERT_IDS] = (current + identifier).toList().takeLast(300).toSet()
        }
    }

    // ── Permission State ─────────────────────────────────────────────────────
    private val KEY_LOCATION_PERMISSION_SKIPPED = booleanPreferencesKey("location_permission_skipped")

    val locationPermissionSkipped: Flow<Boolean> = dataStore.data.map { it[KEY_LOCATION_PERMISSION_SKIPPED] ?: false }

    suspend fun setLocationPermissionSkipped(skipped: Boolean) {
        dataStore.edit { it[KEY_LOCATION_PERMISSION_SKIPPED] = skipped }
    }

    // ── Legacy clean-up markers ──────────────────────────────────────────────
    // Demo Mode (removed) replayed bench-test data straight into the real
    // history tables with no source column, so on any install where it was
    // ever switched on, that history cannot be trusted. The mere presence of
    // its old key is the signal - the one-time wipe consumes it.
    private val KEY_LEGACY_DEMO_MODE      = booleanPreferencesKey("demo_mode_enabled")
    private val KEY_LEGACY_CLEANUP_V1     = booleanPreferencesKey("legacy_cleanup_v1_done")
    private val KEY_PENDING_REMOTE_DELETES = stringSetPreferencesKey("pending_remote_doc_deletes")

    /** True if Demo Mode was ever enabled on this install (the old key still exists). */
    suspend fun hasLegacyDemoMarker(): Boolean =
        dataStore.data.map { it.contains(KEY_LEGACY_DEMO_MODE) }.first()

    /**
     * Removes the Demo Mode marker and records when its data was wiped, so a
     * Drive backup taken before that moment is not allowed to restore the
     * Demo-derived baselines. Called only after the wipe has succeeded.
     */
    suspend fun completeLegacyDemoWipe(atMs: Long) {
        dataStore.edit {
            it.remove(KEY_LEGACY_DEMO_MODE)
            it[KEY_LEGACY_DEMO_WIPE_AT] = atMs
        }
    }

    private val KEY_LEGACY_DEMO_WIPE_AT = longPreferencesKey("legacy_demo_wipe_at_ms")

    /** When Demo-contaminated history was wiped, or null if it never needed to be. */
    val legacyDemoWipeAtMs: Flow<Long?> = dataStore.data.map { it[KEY_LEGACY_DEMO_WIPE_AT] }

    // Set when the removed test button may have left a fabricated document in
    // the sharing store that no local row points to any more.
    private val KEY_FABRICATED_DOC_SWEEP = booleanPreferencesKey("fabricated_doc_sweep_pending")

    val fabricatedDocSweepPending: Flow<Boolean> = dataStore.data.map { it[KEY_FABRICATED_DOC_SWEEP] ?: false }

    suspend fun setFabricatedDocSweepPending(pending: Boolean) {
        dataStore.edit { if (pending) it[KEY_FABRICATED_DOC_SWEEP] = true else it.remove(KEY_FABRICATED_DOC_SWEEP) }
    }

    private val KEY_ANOMALY_REFLAG_DONE = booleanPreferencesKey("legacy_anomaly_reflag_done")

    suspend fun isAnomalyReflagDone(): Boolean =
        dataStore.data.map { it[KEY_ANOMALY_REFLAG_DONE] ?: false }.first()

    suspend fun markAnomalyReflagDone() {
        dataStore.edit { it[KEY_ANOMALY_REFLAG_DONE] = true }
    }

    suspend fun isLegacyCleanupV1Done(): Boolean =
        dataStore.data.map { it[KEY_LEGACY_CLEANUP_V1] ?: false }.first()

    suspend fun markLegacyCleanupV1Done() {
        dataStore.edit { it[KEY_LEGACY_CLEANUP_V1] = true }
    }

    /**
     * Firestore document paths (relative to users/{uid}) that must be deleted
     * on the next sharing run - e.g. the fabricated row the removed "Force
     * Firebase Upload" test button used to push.
     */
    val pendingRemoteDocDeletes: Flow<Set<String>> =
        dataStore.data.map { it[KEY_PENDING_REMOTE_DELETES] ?: emptySet() }

    suspend fun addPendingRemoteDocDeletes(paths: Collection<String>) {
        if (paths.isEmpty()) return
        dataStore.edit { it[KEY_PENDING_REMOTE_DELETES] = (it[KEY_PENDING_REMOTE_DELETES] ?: emptySet()) + paths }
    }

    suspend fun removePendingRemoteDocDeletes(paths: Collection<String>) {
        dataStore.edit { it[KEY_PENDING_REMOTE_DELETES] = (it[KEY_PENDING_REMOTE_DELETES] ?: emptySet()) - paths.toSet() }
    }

    suspend fun clearFirstReadingMs() {
        dataStore.edit { it.remove(KEY_FIRST_READING_MS) }
    }

    // Set when data already shared to the cloud is known to be contaminated
    // (e.g. uploaded while Demo Mode was on); the cloud-deletion worker consumes it.
    private val KEY_REMOTE_PURGE_REQUESTED = booleanPreferencesKey("remote_purge_requested")

    val remotePurgeRequested: Flow<Boolean> = dataStore.data.map { it[KEY_REMOTE_PURGE_REQUESTED] ?: false }

    suspend fun requestRemotePurge() {
        dataStore.edit { it[KEY_REMOTE_PURGE_REQUESTED] = true }
    }

    suspend fun clearRemotePurgeRequest() {
        dataStore.edit { it.remove(KEY_REMOTE_PURGE_REQUESTED) }
    }

    // ── Last known location ──────────────────────────────────────────────────
    // Cached so alert geo-filtering and weather still work on the first launch
    // after a cold start, before a fresh fix arrives.
    private val KEY_LAST_LAT      = stringPreferencesKey("last_lat")
    private val KEY_LAST_LON      = stringPreferencesKey("last_lon")
    private val KEY_LAST_DISTRICT = stringPreferencesKey("last_district")
    private val KEY_LAST_STATE    = stringPreferencesKey("last_state")

    private val KEY_LAST_LOC_AT   = longPreferencesKey("last_location_at_ms")

    data class CachedLocation(
        val lat: Double, val lon: Double,
        val district: String?, val state: String?,
        /** When the fix was taken; null for caches written before this was recorded. */
        val atMs: Long? = null
    )

    val cachedLocation: Flow<CachedLocation?> = dataStore.data.map { prefs ->
        val lat = prefs[KEY_LAST_LAT]?.toDoubleOrNull() ?: return@map null
        val lon = prefs[KEY_LAST_LON]?.toDoubleOrNull() ?: return@map null
        CachedLocation(lat, lon, prefs[KEY_LAST_DISTRICT], prefs[KEY_LAST_STATE], prefs[KEY_LAST_LOC_AT])
    }

    /** Forgets the last position - called when location permission is not (or no longer) granted. */
    suspend fun clearCachedLocation() {
        dataStore.edit {
            it.remove(KEY_LAST_LAT); it.remove(KEY_LAST_LON)
            it.remove(KEY_LAST_DISTRICT); it.remove(KEY_LAST_STATE); it.remove(KEY_LAST_LOC_AT)
        }
    }

    suspend fun cacheLocation(lat: Double, lon: Double, district: String?, state: String?, atMs: Long = System.currentTimeMillis()) {
        dataStore.edit {
            it[KEY_LAST_LAT] = lat.toString()
            it[KEY_LAST_LON] = lon.toString()
            it[KEY_LAST_LOC_AT] = atMs
            district?.takeIf { d -> d.isNotBlank() }?.let { d -> it[KEY_LAST_DISTRICT] = d }
            state?.takeIf    { s -> s.isNotBlank() }?.let { s -> it[KEY_LAST_STATE]    = s }
        }
    }

    // ── Environmental cache ──────────────────────────────────────────────────
    // Stored as one JSON blob rather than a spread of scalar keys. The old
    // scalar version could not represent "we don't know": every read applied a
    // default (28 C, 60 %, UV 4.0, AQI 80), so a device that had never
    // completed a single successful fetch still rendered a confident and
    // entirely invented weather report. Nullable fields survive this round-trip.
    // Alerts are cached too - they previously vanished the moment the app went
    // offline, which is exactly when a disaster warning matters most.
    private val KEY_ENV_SNAPSHOT_JSON = stringPreferencesKey("env_snapshot_json")
    private val KEY_ENV_FETCHED_AT_MS = longPreferencesKey("env_fetched_at_ms")

    val cachedEnvFetchedAtMs: Flow<Long?> = dataStore.data.map { it[KEY_ENV_FETCHED_AT_MS] }

    /** Wire format for the environmental cache. Every measured field nullable. */
    @Serializable
    data class CachedEnvData(
        val tempC:             Float?  = null,
        val humidity:          Float?  = null,
        val uvIndex:           Float?  = null,
        val pressureHpa:       Float?  = null,
        val windSpeedKph:      Float?  = null,
        val aqiValue:          Int?    = null,
        val aqiSource:         String  = "NONE",
        val regionalAqiValue:  Int?    = null,
        val aqiPollutant:      String? = null,
        // Old caches carried pm25/pm10 (sub-indices mislabelled as
        // concentrations); unknown keys are ignored on decode.
        val pollutantSubIndices: Map<String, Int> = emptyMap(),
        val aqiStationName:    String? = null,
        val aqiStationDistanceKm: Double? = null,
        val alertSeverity:     String  = "NONE",
        val alerts:            List<ImdAlert>       = emptyList(),
        // Caches written before these fields existed decode to "never checked",
        // so an old cache can never be read back as a verified all-clear.
        val alertsCheckedAtMs: Long    = 0L,
        val alertSourcesChecked: List<String> = emptyList(),
        val alertCheckFailed:  Boolean = false,
        val advisories:        List<HealthAdvisory> = emptyList(),
        val districtName:      String? = null,
        val stateName:         String? = null,
        val latitude:          Double? = null,
        val longitude:         Double? = null,
        val fetchedAtMs:       Long    = 0L
    )

    suspend fun cacheEnvironmentalSnapshot(data: CachedEnvData) {
        val encoded = runCatching {
            envJson.encodeToString(CachedEnvData.serializer(), data)
        }.getOrNull() ?: return
        dataStore.edit {
            it[KEY_ENV_SNAPSHOT_JSON] = encoded
            it[KEY_ENV_FETCHED_AT_MS] = data.fetchedAtMs
        }
    }

    val cachedEnvironmentalData: Flow<CachedEnvData?> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_ENV_SNAPSHOT_JSON] ?: return@map null
        runCatching { envJson.decodeFromString(CachedEnvData.serializer(), raw) }.getOrNull()
    }

    // ── Plan 9: Settings ─────────────────────────────────────────────────────
    private val KEY_THEME_PREFERENCE       = intPreferencesKey("theme_preference")
    private val KEY_CLOUD_BACKUP_ENABLED   = booleanPreferencesKey("cloud_backup_enabled")

    val themePreference: Flow<ThemePreference> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_PREFERENCE]?.let { ThemePreference.entries.getOrNull(it) } ?: ThemePreference.DARK
    }

    suspend fun setThemePreference(pref: ThemePreference) {
        dataStore.edit { it[KEY_THEME_PREFERENCE] = pref.ordinal }
    }

    val cloudBackupEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_CLOUD_BACKUP_ENABLED] ?: false }

    suspend fun setCloudBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CLOUD_BACKUP_ENABLED] = enabled }
    }

    // ── Cloud (sharing) deletion ─────────────────────────────────────────────
    // The uid whose shared data must be deleted. Survives clearLocalDataPrefs,
    // because the local wipe happens immediately while the cloud deletion may
    // have to wait for a network connection.
    private val KEY_PENDING_CLOUD_DELETION_UID = stringPreferencesKey("pending_cloud_deletion_uid")
    private val KEY_CLOUD_DELETION_STATUS      = stringPreferencesKey("cloud_deletion_status")
    private val KEY_CLOUD_DELETION_COUNT       = intPreferencesKey("cloud_deletion_count")
    private val KEY_CLOUD_DELETION_AT          = longPreferencesKey("cloud_deletion_finished_at_ms")
    private val KEY_CLOUD_ACCOUNT_REMOVED      = booleanPreferencesKey("cloud_deletion_account_removed")
    // Set by a local wipe, cleared once onboarding has been reset. If the
    // process dies between the two, start-up finishes the reset.
    private val KEY_ONBOARDING_RESET_PENDING   = booleanPreferencesKey("onboarding_reset_pending")

    val pendingCloudDeletionUid: Flow<String?> = dataStore.data.map { it[KEY_PENDING_CLOUD_DELETION_UID] }

    suspend fun setPendingCloudDeletionUid(uid: String?) {
        dataStore.edit { if (uid == null) it.remove(KEY_PENDING_CLOUD_DELETION_UID) else it[KEY_PENDING_CLOUD_DELETION_UID] = uid }
    }

    val cloudDeletionStatus: Flow<com.bipss.app.data.sharing.CloudDeletionStatus> = dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_DELETION_STATUS]
            ?.let { runCatching { com.bipss.app.data.sharing.CloudDeletionStatus.valueOf(it) }.getOrNull() }
            ?: com.bipss.app.data.sharing.CloudDeletionStatus.NONE
    }
    val cloudDeletionCount: Flow<Int> = dataStore.data.map { it[KEY_CLOUD_DELETION_COUNT] ?: 0 }
    val cloudDeletionFinishedAtMs: Flow<Long?> = dataStore.data.map { it[KEY_CLOUD_DELETION_AT] }
    /** Whether the last finished deletion also removed the anonymous Firebase account (not just signed out). */
    val cloudDeletionAccountRemoved: Flow<Boolean> = dataStore.data.map { it[KEY_CLOUD_ACCOUNT_REMOVED] ?: false }
    val onboardingResetPending: Flow<Boolean> = dataStore.data.map { it[KEY_ONBOARDING_RESET_PENDING] ?: false }

    suspend fun setCloudDeletionStatus(status: com.bipss.app.data.sharing.CloudDeletionStatus, count: Int) {
        dataStore.edit {
            it[KEY_CLOUD_DELETION_STATUS] = status.name
            it[KEY_CLOUD_DELETION_COUNT] = count
        }
    }

    /**
     * Records a finished deletion and resets the sharing identity. With
     * [keepLocalSharingState] (a contamination purge) the opt-in stays as it
     * was; the next upload signs in as a new identity and starts from scratch.
     */
    suspend fun onCloudDeletionFinished(deletedCount: Int, keepLocalSharingState: Boolean, accountRemoved: Boolean) {
        dataStore.edit {
            it.remove(KEY_PENDING_CLOUD_DELETION_UID)
            it[KEY_CLOUD_DELETION_STATUS] = com.bipss.app.data.sharing.CloudDeletionStatus.DONE.name
            it[KEY_CLOUD_DELETION_COUNT] = deletedCount
            it[KEY_CLOUD_DELETION_AT] = System.currentTimeMillis()
            it[KEY_CLOUD_ACCOUNT_REMOVED] = accountRemoved
            it.remove(KEY_SHARING_FIREBASE_UID)
            it.remove(KEY_SHARING_LAST_AGG_MINUTE)
            it.remove(KEY_SHARING_LAST_SLEEP_MS)
            it.remove(KEY_SHARING_LAST_DESAT_MS)
            it.remove(KEY_PENDING_REMOTE_DELETES)
            it.remove(KEY_FABRICATED_DOC_SWEEP)
            if (keepLocalSharingState) it.remove(KEY_REMOTE_PURGE_REQUESTED)
        }
    }

    suspend fun resetOnboarding() {
        dataStore.edit {
            it.remove(KEY_ONBOARDING_DONE)
            it.remove(KEY_ONBOARDING_RESET_PENDING)
        }
    }

    // ── Drive backup metadata ────────────────────────────────────────────────
    private val KEY_LAST_BACKUP_AT_MS  = longPreferencesKey("last_backup_at_ms")
    private val KEY_LAST_RESTORE_AT_MS = longPreferencesKey("last_restore_at_ms")

    val lastBackupAtMs:  Flow<Long?> = dataStore.data.map { it[KEY_LAST_BACKUP_AT_MS] }
    val lastRestoreAtMs: Flow<Long?> = dataStore.data.map { it[KEY_LAST_RESTORE_AT_MS] }

    suspend fun setLastBackupAtMs(ms: Long)  { dataStore.edit { it[KEY_LAST_BACKUP_AT_MS]  = ms } }
    suspend fun setLastRestoreAtMs(ms: Long) { dataStore.edit { it[KEY_LAST_RESTORE_AT_MS] = ms } }

    /**
     * Clears every preference except the few that must outlive a local wipe:
     *  - the pending cloud-deletion uid and its status (the cloud side may
     *    still be running, and its outcome must stay visible);
     *  - the onboarding flag, which is reset separately once the user has
     *    seen the deletion result - clearing it here would yank the app back
     *    to the welcome screen mid-dialog. A reset-pending marker is written
     *    instead, so a process death before "Done" still ends in onboarding;
     *  - the downloaded model's location (the model is not personal data,
     *    and forgetting it would orphan a large file);
     *  - the theme.
     * Sharing consent and identity are cleared: opting in again starts over.
     */
    suspend fun clearLocalDataPrefs() {
        dataStore.edit { prefs ->
            val keep = listOf<Preferences.Key<*>>(
                KEY_PENDING_CLOUD_DELETION_UID, KEY_CLOUD_DELETION_STATUS, KEY_CLOUD_DELETION_COUNT,
                KEY_CLOUD_DELETION_AT, KEY_CLOUD_ACCOUNT_REMOVED, KEY_ONBOARDING_DONE, KEY_MODEL_VERSION,
                KEY_MODEL_FILE_PATH, KEY_MODEL_SIZE_BYTES, KEY_THEME_PREFERENCE
            )
            val saved = keep.mapNotNull { key -> prefs.asMap()[key]?.let { key to it } }
            prefs.clear()
            @Suppress("UNCHECKED_CAST")
            saved.forEach { (key, value) -> prefs[key as Preferences.Key<Any>] = value }
            prefs[KEY_ONBOARDING_RESET_PENDING] = true
        }
    }

    // ── Data Sharing ─────────────────────────────────────────────────────────
    private val KEY_DATA_SHARING_ENABLED      = booleanPreferencesKey("data_sharing_enabled")
    private val KEY_SHARING_USERNAME           = stringPreferencesKey("sharing_username")
    private val KEY_SHARING_FIREBASE_UID       = stringPreferencesKey("sharing_firebase_uid")
    private val KEY_SHARING_LAST_AGG_MINUTE    = longPreferencesKey("sharing_last_agg_minute")
    private val KEY_SHARING_LAST_SLEEP_MS      = longPreferencesKey("sharing_last_sleep_ms")
    private val KEY_SHARING_LAST_DESAT_MS      = longPreferencesKey("sharing_last_desat_ms")

    val dataSharingEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_DATA_SHARING_ENABLED] ?: false }

    suspend fun setDataSharingEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DATA_SHARING_ENABLED] = enabled }
    }

    val sharingUsername: Flow<String?> = dataStore.data.map { it[KEY_SHARING_USERNAME] }

    /**
     * Returns the existing sharing username, or generates a new BIPSS-XXXX code
     * on first opt-in. The username persists across toggle off/on; a new one is
     * only generated after "Delete my data" clears all prefs.
     */
    suspend fun getOrCreateSharingUsername(): String {
        val existing = dataStore.data.map { it[KEY_SHARING_USERNAME] }.first()
        if (existing != null) return existing
        val username = "BIPSS-" + UUID.randomUUID().toString().take(4)
        dataStore.edit { it[KEY_SHARING_USERNAME] = username }
        return username
    }

    val sharingFirebaseUid: Flow<String?> = dataStore.data.map { it[KEY_SHARING_FIREBASE_UID] }

    suspend fun setSharingFirebaseUid(uid: String) {
        dataStore.edit { it[KEY_SHARING_FIREBASE_UID] = uid }
    }

    // ── Sharing watermarks (incremental sync) ────────────────────────────────
    val lastSyncedAggMinute: Flow<Long> = dataStore.data.map { it[KEY_SHARING_LAST_AGG_MINUTE] ?: 0L }

    suspend fun setLastSyncedAggMinute(minuteEpoch: Long) {
        dataStore.edit { it[KEY_SHARING_LAST_AGG_MINUTE] = minuteEpoch }
    }

    val lastSyncedSleepMs: Flow<Long> = dataStore.data.map { it[KEY_SHARING_LAST_SLEEP_MS] ?: 0L }

    suspend fun setLastSyncedSleepMs(ms: Long) {
        dataStore.edit { it[KEY_SHARING_LAST_SLEEP_MS] = ms }
    }

    val lastSyncedDesatMs: Flow<Long> = dataStore.data.map { it[KEY_SHARING_LAST_DESAT_MS] ?: 0L }

    suspend fun setLastSyncedDesatMs(ms: Long) {
        dataStore.edit { it[KEY_SHARING_LAST_DESAT_MS] = ms }
    }
}

/** Simple domain model for user profile stored in DataStore */
data class UserProfile(
    val name:     String,
    val age:      Int,
    val heightCm: Float,
    val weightKg: Float
)
