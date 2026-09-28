package com.bipss.app.data.backup

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.UserProfile
import com.bipss.app.data.db.dao.BaselineStatsDao
import com.bipss.app.data.db.dao.EmergencyContactDao
import com.bipss.app.data.db.entity.BaselineStatsEntity
import com.bipss.app.data.db.entity.EmergencyContactEntity
import com.bipss.app.ui.theme.ThemePreference
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DriveBackupManager"
private const val BACKUP_FILE_NAME = "bipss_backup.json"
private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

@Serializable
data class BackupContact(val name: String, val phoneNumber: String, val addedAtMs: Long)

@Serializable
data class BackupBaseline(val metricType: String, val mean: Double, val m2: Double, val count: Long, val stdDev: Double)

@Serializable
data class BackupPayload(
    val backedUpAtMs: Long,
    val profileName: String?,
    val profileAge: Int?,
    val profileHeightCm: Float?,
    val profileWeightKg: Float?,
    val themePreferenceOrdinal: Int,
    val emergencyContacts: List<BackupContact>,
    val baselineStats: List<BackupBaseline>
)

sealed class BackupResult {
    object Success : BackupResult()
    data class NotSignedIn(val signInIntent: Intent) : BackupResult()
    data class Failure(val message: String) : BackupResult()
}

/**
 * Backs up (and restores) BIPSS's non-sensitive settings/summary data to the
 * signed-in user's Google Drive "app data" folder — a space that is
 * invisible in the user's normal Drive UI and unreadable by any app other
 * than this one. This is the closest real equivalent to "locked to our app"
 * while still solving cross-device migration without a custom backend.
 *
 * Deliberately excludes raw_readings (large, low-value, and the whole point
 * of keeping sensor data device-private) — only baseline stats, emergency
 * contacts, and settings are backed up.
 *
 * TODO: Requires a real OAuth 2.0 Android client ID registered in Google
 * Cloud Console (Drive API enabled, OAuth consent screen configured, client
 * tied to this app's package name + signing certificate SHA-1) before
 * Google's servers will actually grant the drive.appdata scope — same
 * "external credential required" pattern as CPCB_AQI_API_KEY in
 * local.properties and MODEL_MANIFEST_URL. Until that's configured, sign-in
 * itself will work but scope consent will fail; backupNow()/restoreLatest()
 * surface that as [BackupResult.Failure] rather than crashing.
 */
class DriveBackupManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val prefsRepo: UserPreferencesRepository,
    private val emergencyContactDao: EmergencyContactDao,
    private val baselineStatsDao: BaselineStatsDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun signInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun lastSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun hasDriveAppDataScope(account: GoogleSignInAccount): Boolean =
        GoogleSignIn.hasPermissions(account, Scope(DRIVE_APPDATA_SCOPE))

    private suspend fun accessToken(account: GoogleSignInAccount): String = withContext(Dispatchers.IO) {
        val androidAccount: Account = account.account
            ?: throw IllegalStateException("Signed-in Google account has no underlying Account")
        GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_APPDATA_SCOPE")
    }

    suspend fun backupNow(): BackupResult {
        val account = lastSignedInAccount()
        if (account == null || !hasDriveAppDataScope(account)) {
            return BackupResult.NotSignedIn(signInClient().signInIntent)
        }
        return try {
            withContext(Dispatchers.IO) {
                val token = accessToken(account)
                val payload = buildPayload()
                val body = json.encodeToString(BackupPayload.serializer(), payload)
                val existingFileId = findBackupFileId(token)
                if (existingFileId != null) updateFile(token, existingFileId, body)
                else createFile(token, body)
            }
            prefsRepo.setLastBackupAtMs(System.currentTimeMillis())
            BackupResult.Success
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed: ${e.message}")
            BackupResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun restoreLatest(): BackupResult {
        val account = lastSignedInAccount()
        if (account == null || !hasDriveAppDataScope(account)) {
            return BackupResult.NotSignedIn(signInClient().signInIntent)
        }
        return try {
            withContext(Dispatchers.IO) {
                val token = accessToken(account)
                val fileId = findBackupFileId(token)
                if (fileId == null) {
                    BackupResult.Failure("No backup found in Drive.")
                } else {
                    val body = downloadFile(token, fileId)
                    val payload = json.decodeFromString(BackupPayload.serializer(), body)
                    applyPayload(payload)
                    prefsRepo.setLastRestoreAtMs(System.currentTimeMillis())
                    BackupResult.Success
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed: ${e.message}")
            BackupResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun buildPayload(): BackupPayload {
        val profile: UserProfile? = prefsRepo.userProfile.first()
        val theme: ThemePreference = prefsRepo.themePreference.first()
        val contacts = emergencyContactDao.all().first().map {
            BackupContact(it.name, it.phoneNumber, it.addedAtMs)
        }
        val baselines = baselineStatsDao.all().first().map {
            BackupBaseline(it.metricType, it.mean, it.m2, it.count, it.stdDev)
        }
        return BackupPayload(
            backedUpAtMs = System.currentTimeMillis(),
            profileName = profile?.name,
            profileAge = profile?.age,
            profileHeightCm = profile?.heightCm,
            profileWeightKg = profile?.weightKg,
            themePreferenceOrdinal = theme.ordinal,
            emergencyContacts = contacts,
            baselineStats = baselines
        )
    }

    private suspend fun applyPayload(payload: BackupPayload) {
        if (payload.profileName != null) {
            prefsRepo.saveUserProfile(
                UserProfile(
                    name = payload.profileName,
                    age = payload.profileAge ?: 0,
                    heightCm = payload.profileHeightCm ?: 0f,
                    weightKg = payload.profileWeightKg ?: 0f
                )
            )
        }
        prefsRepo.setThemePreference(
            ThemePreference.entries.getOrElse(payload.themePreferenceOrdinal) { ThemePreference.DARK }
        )
        payload.emergencyContacts.forEach {
            emergencyContactDao.insert(EmergencyContactEntity(name = it.name, phoneNumber = it.phoneNumber, addedAtMs = it.addedAtMs))
        }
        // A backup taken before Demo-contaminated history was wiped would bring
        // the bench-test baselines straight back, so its baselines are skipped.
        val demoWipeAt = prefsRepo.legacyDemoWipeAtMs.first()
        if (demoWipeAt == null || payload.backedUpAtMs >= demoWipeAt) {
            payload.baselineStats.forEach {
                baselineStatsDao.upsert(BaselineStatsEntity(it.metricType, it.mean, it.m2, it.count, it.stdDev))
            }
        }
    }

    // ── Drive REST helpers ──────────────────────────────────────────────────

    private fun findBackupFileId(token: String): String? {
        val url = "$DRIVE_FILES_URL?spaces=appDataFolder&q=name='$BACKUP_FILE_NAME'&fields=files(id,name)"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Drive list failed: HTTP ${resp.code}")
            val body = resp.body?.string() ?: return null
            val root = Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            val files = root["files"]?.let { it as kotlinx.serialization.json.JsonArray } ?: return null
            val first = files.firstOrNull()?.let { it as kotlinx.serialization.json.JsonObject } ?: return null
            return first["id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }
    }

    private fun createFile(token: String, content: String) {
        val metadata = """{"name":"$BACKUP_FILE_NAME","parents":["appDataFolder"]}"""
        val boundary = "bipss_backup_boundary"
        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(content)
            append("\r\n--$boundary--")
        }
        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_URL?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipartBody.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Drive create failed: HTTP ${resp.code}")
        }
    }

    private fun updateFile(token: String, fileId: String, content: String) {
        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_URL/$fileId?uploadType=media")
            .header("Authorization", "Bearer $token")
            .patch(content.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Drive update failed: HTTP ${resp.code}")
        }
    }

    private fun downloadFile(token: String, fileId: String): String {
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Drive download failed: HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("Empty backup file")
        }
    }
}
