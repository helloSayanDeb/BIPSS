package com.bipss.app.data.backup

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.bipss.app.data.Gender
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.UserProfile
import com.bipss.app.data.db.dao.BaselineStatsDao
import com.bipss.app.data.db.dao.CycleDao
import com.bipss.app.data.db.dao.DataDayDao
import com.bipss.app.data.db.dao.EmergencyContactDao
import com.bipss.app.data.db.dao.EwmaBaselineDao
import com.bipss.app.data.db.dao.WaterLogDao
import com.bipss.app.data.db.entity.BaselineStatsEntity
import com.bipss.app.data.db.entity.CycleDayNoteEntity
import com.bipss.app.data.db.entity.CyclePeriodEntity
import com.bipss.app.data.db.entity.EmergencyContactEntity
import com.bipss.app.data.db.entity.EwmaBaselineEntity
import com.bipss.app.data.db.entity.WaterLogEntity
import com.bipss.app.ui.theme.ThemePreference
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "DriveBackupManager"
private const val BACKUP_FILE_NAME = "bipss_backup.json"
/** The metric key PRV was stored under before schema v7 (see MIGRATION_6_7). */
private const val LEGACY_VARIABILITY_KEY = "HRV"
private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

@Serializable
data class BackupContact(val name: String, val phoneNumber: String, val addedAtMs: Long)

@Serializable
data class BackupBaseline(val metricType: String, val mean: Double, val m2: Double, val count: Long, val stdDev: Double)

@Serializable
data class BackupEwma(val key: String, val mean: Double, val variance: Double, val count: Int, val lastNightStartMs: Long, val updatedAtMs: Long)

@Serializable
data class BackupDataDay(val epochDay: Long, val validMinutes: Int)

@Serializable
data class BackupWater(val timestampMs: Long, val amountMl: Int)

@Serializable
data class BackupPeriod(val startEpochDay: Long, val endEpochDay: Long? = null)

@Serializable
data class BackupNote(val epochDay: Long, val note: String, val updatedAtMs: Long)

/**
 * Everything a new phone needs to carry on where the old one left off -
 * except raw sensor readings, which stay on the phone that recorded them.
 * Schema 1 (before this release) had the first eight fields only; every
 * field added since has a default, so an old backup still restores.
 */
@Serializable
data class BackupPayload(
    val backedUpAtMs: Long,
    val profileName: String?,
    val profileAge: Int?,
    val profileHeightCm: Float?,
    val profileWeightKg: Float?,
    val themePreferenceOrdinal: Int,
    val emergencyContacts: List<BackupContact>,
    val baselineStats: List<BackupBaseline>,
    val schemaVersion: Int = 1,
    val profileGender: String? = null,
    val ewmaBaselines: List<BackupEwma> = emptyList(),
    val dataDays: List<BackupDataDay> = emptyList(),
    val waterLog: List<BackupWater> = emptyList(),
    val cyclePeriods: List<BackupPeriod> = emptyList(),
    val cycleNotes: List<BackupNote> = emptyList(),
    val waterGoalMl: Int? = null
)

sealed class BackupResult {
    data class Success(val detail: String) : BackupResult()
    /** Drive access needs the user's consent: launch it, then repeat the action. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : BackupResult()
    data class Failure(val message: String) : BackupResult()
}

/**
 * Backs up to, and restores from, the user's own Google Drive: the hidden
 * app-data folder, which only this app can read. The user's own copy for
 * moving to a new phone - nothing reaches the developer. (Separate from the
 * opt-in data sharing, which is never a backup.)
 *
 * Access is through Google Identity's AuthorizationClient: one call either
 * returns a token or a consent screen to show, after which the same action
 * is simply repeated. It needs an Android OAuth client for this package and
 * signing certificate in the Google Cloud project (docs: Drive backup set-up);
 * without one Google answers DEVELOPER_ERROR, reported as "not set up".
 */
class DriveBackupManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val prefsRepo: UserPreferencesRepository,
    private val emergencyContactDao: EmergencyContactDao,
    private val baselineStatsDao: BaselineStatsDao,
    private val ewmaDao: EwmaBaselineDao,
    private val dataDayDao: DataDayDao,
    private val waterDao: WaterLogDao,
    private val cycleDao: CycleDao
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private sealed interface Access {
        data class Token(val value: String) : Access
        data class Consent(val pendingIntent: PendingIntent) : Access
        data class Refused(val message: String) : Access
    }

    private suspend fun authorize(): Access = try {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        val token = result.accessToken
        when {
            result.hasResolution() && result.pendingIntent != null -> Access.Consent(result.pendingIntent!!)
            token != null -> Access.Token(token)
            else -> Access.Refused("Google didn't grant Drive access.")
        }
    } catch (e: ApiException) {
        Log.w(TAG, "Drive authorization failed: ${e.statusCode} ${e.message}")
        Access.Refused(
            if (e.statusCode == CommonStatusCodes.DEVELOPER_ERROR)
                "Drive backup isn't set up for this build yet (its Google Cloud sign-in client is missing)."
            else "Couldn't get Drive access (${CommonStatusCodes.getStatusCodeString(e.statusCode)})."
        )
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        Log.w(TAG, "Drive authorization failed: ${e.message}")
        Access.Refused("Couldn't get Drive access: ${e.message}")
    }

    /** Grants access only: no health payload is built, uploaded or restored. */
    suspend fun connect(): BackupResult = when (val access = authorize()) {
        is Access.Token -> BackupResult.Success("Google Drive access granted. Choose Back up now when you're ready to save a copy.")
        is Access.Consent -> BackupResult.NeedsConsent(access.pendingIntent)
        is Access.Refused -> BackupResult.Failure(access.message)
    }

    suspend fun backupNow(): BackupResult {
        if (com.bipss.app.SimulationMode.isActive(context)) {
            return BackupResult.Failure("Backup is off while the simulated band is in use.")
        }
        val token = when (val access = authorize()) {
            is Access.Token -> access.value
            is Access.Consent -> return BackupResult.NeedsConsent(access.pendingIntent)
            is Access.Refused -> return BackupResult.Failure(access.message)
        }
        return try {
            val payload = buildPayload()
            withContext(Dispatchers.IO) {
                val body = json.encodeToString(BackupPayload.serializer(), payload)
                val existingFileId = findBackupFileId(token)
                if (existingFileId != null) updateFile(token, existingFileId, body)
                else createFile(token, body)
            }
            prefsRepo.setLastBackupAtMs(payload.backedUpAtMs)
            BackupResult.Success(
                "Backed up to your Google Drive: profile, ${payload.emergencyContacts.size} contact(s), baselines, " +
                    "${payload.waterLog.size} drink(s), ${payload.cyclePeriods.size} period(s)."
            )
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed: ${e.message}")
            BackupResult.Failure("Backup failed: ${e.message ?: "unknown error"}")
        }
    }

    suspend fun restoreLatest(): BackupResult {
        if (com.bipss.app.SimulationMode.isActive(context)) {
            return BackupResult.Failure("Restore is off while the simulated band is in use.")
        }
        val token = when (val access = authorize()) {
            is Access.Token -> access.value
            is Access.Consent -> return BackupResult.NeedsConsent(access.pendingIntent)
            is Access.Refused -> return BackupResult.Failure(access.message)
        }
        return try {
            val body = withContext(Dispatchers.IO) {
                val fileId = findBackupFileId(token) ?: return@withContext null
                downloadFile(token, fileId)
            } ?: return BackupResult.Failure("There's no backup in this Google account's Drive.")
            val payload = json.decodeFromString(BackupPayload.serializer(), body)
            val summary = applyPayload(payload)
            prefsRepo.setLastRestoreAtMs(System.currentTimeMillis())
            BackupResult.Success(summary)
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed: ${e.message}")
            BackupResult.Failure("Restore failed: ${e.message ?: "unknown error"}")
        }
    }

    private suspend fun buildPayload(): BackupPayload {
        val profile: UserProfile? = prefsRepo.userProfile.first()
        val theme: ThemePreference = prefsRepo.themePreference.first()
        return BackupPayload(
            backedUpAtMs           = System.currentTimeMillis(),
            profileName            = profile?.name,
            profileAge             = profile?.age,
            profileHeightCm        = profile?.heightCm,
            profileWeightKg        = profile?.weightKg,
            themePreferenceOrdinal = theme.ordinal,
            emergencyContacts      = emergencyContactDao.all().first().map { BackupContact(it.name, it.phoneNumber, it.addedAtMs) },
            baselineStats          = baselineStatsDao.all().first().map { BackupBaseline(it.metricType, it.mean, it.m2, it.count, it.stdDev) },
            schemaVersion          = 2,
            profileGender          = profile?.gender?.name,
            ewmaBaselines          = ewmaDao.all().map { BackupEwma(it.key, it.mean, it.variance, it.count, it.lastNightStartMs, it.updatedAtMs) },
            dataDays               = dataDayDao.all().map { BackupDataDay(it.epochDay, it.validMinutes) },
            waterLog               = waterDao.all().map { BackupWater(it.timestampMs, it.amountMl) },
            cyclePeriods           = cycleDao.periodsNow().map { BackupPeriod(it.startEpochDay, it.endEpochDay) },
            cycleNotes             = cycleDao.allNotes().map { BackupNote(it.epochDay, it.note, it.updatedAtMs) },
            waterGoalMl            = prefsRepo.waterGoalMl.first()
        )
    }

    /**
     * Merges the backup into what is already on this phone - nothing is
     * duplicated and nothing newer is overwritten - and says what came back.
     */
    private suspend fun applyPayload(payload: BackupPayload): String {
        if (payload.profileName != null) {
            prefsRepo.saveUserProfile(
                UserProfile(
                    name     = payload.profileName,
                    age      = payload.profileAge ?: 0,
                    heightCm = payload.profileHeightCm ?: 0f,
                    weightKg = payload.profileWeightKg ?: 0f,
                    gender   = Gender.fromKey(payload.profileGender)
                )
            )
        }
        prefsRepo.setThemePreference(
            ThemePreference.entries.getOrElse(payload.themePreferenceOrdinal) { ThemePreference.DARK }
        )
        if (prefsRepo.waterGoalMl.first() == null) {
            payload.waterGoalMl?.takeIf { it in 250..5000 }?.let { prefsRepo.setWaterGoalMl(it) }
        }

        // Contacts: the same number (digits only) is the same person.
        val existing = emergencyContactDao.all().first().map { digits(it.phoneNumber) }.toMutableSet()
        var contactsAdded = 0
        payload.emergencyContacts.forEach {
            if (existing.add(digits(it.phoneNumber))) {
                emergencyContactDao.insert(EmergencyContactEntity(name = it.name, phoneNumber = it.phoneNumber, addedAtMs = it.addedAtMs))
                contactsAdded++
            }
        }

        // A backup taken before Demo-contaminated history was wiped would bring
        // the bench-test baselines straight back, so its baselines are skipped.
        val demoWipeAt = prefsRepo.legacyDemoWipeAtMs.first()
        if (demoWipeAt == null || payload.backedUpAtMs >= demoWipeAt) {
            payload.baselineStats.forEach {
                // Backups made before schema v7 use the old variability key.
                val type = if (it.metricType == LEGACY_VARIABILITY_KEY) com.bipss.app.data.MetricTypes.PRV else it.metricType
                baselineStatsDao.upsert(BaselineStatsEntity(type, it.mean, it.m2, it.count, it.stdDev))
            }
        }
        // Nightly baselines: the backup's where this phone has none or an older one.
        val localEwma = ewmaDao.all().associateBy { it.key }
        ewmaDao.upsertAll(payload.ewmaBaselines.filter { b -> (localEwma[b.key]?.updatedAtMs ?: 0L) < b.updatedAtMs }
            .map { EwmaBaselineEntity(it.key, it.mean, it.variance, it.count, it.lastNightStartMs, it.updatedAtMs) })
        payload.dataDays.forEach { dataDayDao.ensure(it.epochDay); dataDayDao.atLeast(it.epochDay, it.validMinutes) }

        val drinksHere = waterDao.all().map { it.timestampMs }.toHashSet()
        val drinks = payload.waterLog.filter { it.timestampMs !in drinksHere && it.amountMl in 1..5000 }
        drinks.forEach { waterDao.insert(WaterLogEntity(timestampMs = it.timestampMs, amountMl = it.amountMl)) }

        val startsHere = cycleDao.periodsNow().map { it.startEpochDay }.toHashSet()
        val periods = payload.cyclePeriods.filter { it.startEpochDay !in startsHere }
        periods.forEach { cycleDao.insertPeriodIgnoringDuplicate(CyclePeriodEntity(startEpochDay = it.startEpochDay, endEpochDay = it.endEpochDay)) }
        var notes = 0
        payload.cycleNotes.forEach { n ->
            val here = cycleDao.note(n.epochDay)
            if (here == null || here.updatedAtMs < n.updatedAtMs) {
                cycleDao.upsertNote(CycleDayNoteEntity(n.epochDay, n.note, n.updatedAtMs)); notes++
            }
        }

        val taken = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(payload.backedUpAtMs))
        return "Restored your backup of $taken: profile, $contactsAdded new contact(s), baselines, " +
            "${drinks.size} drink(s), ${periods.size} period(s), $notes note(s)."
    }

    private fun digits(phone: String) = phone.filter { it.isDigit() }.takeLast(10)

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

/** Awaits a Play-services Task without pulling in another library. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
