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

    // ── Water log ────────────────────────────────────────────────────────────
    private val KEY_LAST_WATER_LOG_MS = longPreferencesKey("last_water_log_ms")
    private val KEY_WATER_LOG_COUNT   = intPreferencesKey("water_log_count_today")
    private val KEY_WATER_LOG_ML      = intPreferencesKey("water_log_ml_today")
    private val KEY_WATER_LOG_DAY     = longPreferencesKey("water_log_day_epoch")

    val lastWaterLogMs: Flow<Long?> = dataStore.data.map { it[KEY_LAST_WATER_LOG_MS] }

    /** Glasses logged today. Resets automatically when the day rolls over. */
    val waterLogCountToday: Flow<Int> = dataStore.data.map { prefs ->
        val day = prefs[KEY_WATER_LOG_DAY] ?: return@map 0
        if (day != todayEpochDay()) 0 else prefs[KEY_WATER_LOG_COUNT] ?: 0
    }

    /** Total mL logged today. Resets automatically when the day rolls over. */
    val waterLogTotalMlToday: Flow<Int> = dataStore.data.map { prefs ->
        val day = prefs[KEY_WATER_LOG_DAY] ?: return@map 0
        if (day != todayEpochDay()) 0 else prefs[KEY_WATER_LOG_ML] ?: 0
    }

    suspend fun logWaterIntake(amountMl: Int = 250, timestampMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_WATER_LOG_MS] = timestampMs
            val today = todayEpochDay()
            val sameDay = prefs[KEY_WATER_LOG_DAY] == today
            prefs[KEY_WATER_LOG_DAY]   = today
            prefs[KEY_WATER_LOG_COUNT] = if (sameDay) (prefs[KEY_WATER_LOG_COUNT] ?: 0) + 1 else 1
            prefs[KEY_WATER_LOG_ML]    = if (sameDay) (prefs[KEY_WATER_LOG_ML] ?: 0) + amountMl else amountMl
        }
    }

    /**
     * Hours since the last logged drink, or **null when nothing has ever been
     * logged**.
     *
     * This deliberately does not fall back to 8 hours. That old default fed
     * `dehydMod = (8/8) x 20`, putting a permanent, invented 20 points into
     * every Heat Stress score forever and surfacing "Hydration Low" as a
     * finding when it was actually a constant. Not having logged a drink is
     * not evidence of not having drunk one, so the honest answer is "unknown"
     * and the hydration component drops out of the score entirely.
     */
    suspend fun hoursSinceLastWaterLog(): Float? {
        val last = dataStore.data.map { it[KEY_LAST_WATER_LOG_MS] }.first() ?: return null
        return ((System.currentTimeMillis() - last) / 3_600_000f).coerceIn(0f, 24f)
    }

    private fun todayEpochDay(): Long =
        System.currentTimeMillis() / 86_400_000L

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
    // Without this the 3-hourly sync re-notifies the same warning all day.
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

    // ── Demo mode ────────────────────────────────────────────────────────────
    private val KEY_DEMO_MODE_ENABLED = booleanPreferencesKey("demo_mode_enabled")

    val demoModeEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_DEMO_MODE_ENABLED] ?: false }

    suspend fun setDemoModeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DEMO_MODE_ENABLED] = enabled }
    }

    // ── Last known location ──────────────────────────────────────────────────
    // Cached so alert geo-filtering and weather still work on the first launch
    // after a cold start, before a fresh fix arrives.
    private val KEY_LAST_LAT      = stringPreferencesKey("last_lat")
    private val KEY_LAST_LON      = stringPreferencesKey("last_lon")
    private val KEY_LAST_DISTRICT = stringPreferencesKey("last_district")
    private val KEY_LAST_STATE    = stringPreferencesKey("last_state")

    data class CachedLocation(
        val lat: Double, val lon: Double,
        val district: String?, val state: String?
    )

    val cachedLocation: Flow<CachedLocation?> = dataStore.data.map { prefs ->
        val lat = prefs[KEY_LAST_LAT]?.toDoubleOrNull() ?: return@map null
        val lon = prefs[KEY_LAST_LON]?.toDoubleOrNull() ?: return@map null
        CachedLocation(lat, lon, prefs[KEY_LAST_DISTRICT], prefs[KEY_LAST_STATE])
    }

    suspend fun cacheLocation(lat: Double, lon: Double, district: String?, state: String?) {
        dataStore.edit {
            it[KEY_LAST_LAT] = lat.toString()
            it[KEY_LAST_LON] = lon.toString()
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
        val pm25:              Float?  = null,
        val pm10:              Float?  = null,
        val alertSeverity:     String  = "NONE",
        val alerts:            List<ImdAlert>       = emptyList(),
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
    private val KEY_DELETION_INITIATED_AT  = longPreferencesKey("deletion_initiated_at_ms")

    val themePreference: Flow<ThemePreference> = dataStore.data.map { prefs ->
        ThemePreference.entries.getOrElse(prefs[KEY_THEME_PREFERENCE] ?: 0) { ThemePreference.SYSTEM }
    }

    suspend fun setThemePreference(pref: ThemePreference) {
        dataStore.edit { it[KEY_THEME_PREFERENCE] = pref.ordinal }
    }

    val cloudBackupEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_CLOUD_BACKUP_ENABLED] ?: false }

    suspend fun setCloudBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CLOUD_BACKUP_ENABLED] = enabled }
    }

    val deletionInitiatedAtMs: Flow<Long?> = dataStore.data.map { it[KEY_DELETION_INITIATED_AT] }

    suspend fun initiateCloudDeletion() {
        dataStore.edit { it[KEY_DELETION_INITIATED_AT] = System.currentTimeMillis() }
    }

    // ── Drive backup metadata ────────────────────────────────────────────────
    private val KEY_LAST_BACKUP_AT_MS  = longPreferencesKey("last_backup_at_ms")
    private val KEY_LAST_RESTORE_AT_MS = longPreferencesKey("last_restore_at_ms")

    val lastBackupAtMs:  Flow<Long?> = dataStore.data.map { it[KEY_LAST_BACKUP_AT_MS] }
    val lastRestoreAtMs: Flow<Long?> = dataStore.data.map { it[KEY_LAST_RESTORE_AT_MS] }

    suspend fun setLastBackupAtMs(ms: Long)  { dataStore.edit { it[KEY_LAST_BACKUP_AT_MS]  = ms } }
    suspend fun setLastRestoreAtMs(ms: Long) { dataStore.edit { it[KEY_LAST_RESTORE_AT_MS] = ms } }

    suspend fun clearLocalDataPrefs() {
        dataStore.edit { prefs ->
            // Remove everything except cloud backup toggle
            val cloudBackup = prefs[KEY_CLOUD_BACKUP_ENABLED]
            prefs.clear()
            cloudBackup?.let { prefs[KEY_CLOUD_BACKUP_ENABLED] = it }
            // Data sharing keys are intentionally cleared: consent and
            // identity reset on "Delete my data".
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
