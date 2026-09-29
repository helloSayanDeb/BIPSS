package com.bipss.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val TAG = "ModelDownloadManager"
private const val PROGRESS_INTERVAL_MS = 250L

sealed class DownloadProgress {
    object Idle           : DownloadProgress()
    object Initializing   : DownloadProgress()
    data class Downloading(
        val fraction:                Float,
        val bytesDownloaded:         Long,
        val totalBytes:              Long,
        /** -1 until a speed can be measured. */
        val estimatedSecondsRemaining: Int
    ) : DownloadProgress() {
        val mbDownloaded: Int get() = (bytesDownloaded / 1_048_576L).toInt()
        val totalMb:      Int get() = (totalBytes     / 1_048_576L).toInt()
    }
    object Verifying      : DownloadProgress()
    object Success        : DownloadProgress()
    object AlreadyUpToDate : DownloadProgress()
    data class Failure(val reason: String) : DownloadProgress()
}

/**
 * Handles manifest fetch, download, SHA-256 verification, and model file management.
 * Called from [ModelDownloadViewModel] during onboarding and [SettingsViewModel] for updates.
 */
class ModelDownloadManager(
    private val context:       Context,
    private val okHttpClient:  OkHttpClient,
    private val prefsRepo:     com.bipss.app.data.UserPreferencesRepository
) {
    private val modelDir = File(context.filesDir, "models").also { it.mkdirs() }

    /** Returns the currently installed model file, or null if none. */
    fun installedModelFile(): File? {
        return modelDir.listFiles()?.firstOrNull { it.extension == "bin" }
    }

    /**
     * Fetches the manifest from [MODEL_MANIFEST_URL].
     * Returns null on network failure.
     */
    suspend fun fetchManifest(): ModelManifest? = withContext(Dispatchers.IO) {
        if (!isModelDownloadConfigured) return@withContext null
        try {
            val request  = Request.Builder().url(MODEL_MANIFEST_URL).build()
            val body = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            } ?: return@withContext null
            Json { ignoreUnknownKeys = true }.decodeFromString<ModelManifest>(body)
        } catch (e: Exception) {
            Log.w(TAG, "Manifest fetch failed: ${e.message}")
            null
        }
    }

    /** Public wrapper for Settings "Check for updates" button */
    suspend fun fetchManifestPublic(): ModelManifest? = fetchManifest()

    /**
     * Downloads and verifies the model file if not already up-to-date.
     * Emits [DownloadProgress] updates for UI binding.
     *
     * The blocking I/O runs upstream of [flowOn]. It used to sit inside
     * `withContext(Dispatchers.IO)` in the flow body, and emitting from there
     * breaks the flow's context rule: the first progress update of a real
     * download threw, and the failure it tried to emit threw again.
     */
    fun downloadModelIfNeeded(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Initializing)

        val manifest = fetchManifest()
        if (manifest == null) {
            emit(DownloadProgress.Failure("Could not reach the model server. Check your connection."))
            return@flow
        }

        // Check if already up to date
        val installedVersion = prefsRepo.installedModelVersion.first()
        if (installedVersion == manifest.version && installedModelFile()?.exists() == true) {
            emit(DownloadProgress.AlreadyUpToDate)
            return@flow
        }

        val targetFile = File(modelDir, "bipss_model_${manifest.version}.bin")
        val failure = try {
            downloadTo(targetFile, manifest.downloadUrl, manifest.sizeBytes) { emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Download error: ${e.message}"
        }
        if (failure != null) {
            emit(DownloadProgress.Failure(failure))
            return@flow
        }

        // Verify SHA-256
        emit(DownloadProgress.Verifying)
        val actualHash = sha256(targetFile)
        if (actualHash != manifest.sha256) {
            targetFile.delete()
            emit(DownloadProgress.Failure("Checksum mismatch. Download may be corrupted."))
            return@flow
        }

        // Persist install metadata — sizeBytes comes from the manifest that
        // was actually fetched, never a hardcoded constant.
        prefsRepo.saveInstalledModel(
            version = manifest.version,
            filePath = targetFile.absolutePath,
            sizeBytes = manifest.sizeBytes
        )
        emit(DownloadProgress.Success)
    }.flowOn(Dispatchers.IO)

    /**
     * Streams [url] into [target], resuming a partial file when the server
     * honours Range (a stopped download picks up where it left off). Returns
     * null on success, or why it failed. Progress is reported every
     * [PROGRESS_INTERVAL_MS] rather than once per buffer.
     */
    private suspend fun downloadTo(
        target: File,
        url: String,
        totalBytes: Long,
        onProgress: suspend (DownloadProgress) -> Unit
    ): String? {
        if (target.exists() && target.length() > totalBytes) target.delete()   // oversized: start again
        val existing = if (target.exists()) target.length() else 0L
        if (existing == totalBytes) return null                                 // complete: go and verify

        val request = Request.Builder().url(url)
            .apply { if (existing > 0) addHeader("Range", "bytes=$existing-") }
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            // 206 Partial Content is expected for range requests, 200 for full.
            if (response.code != 200 && response.code != 206) return "Download failed: HTTP ${response.code}"
            val body = response.body ?: return "Empty response"
            val append = response.code == 206
            var downloaded = if (append) existing else 0L
            val sessionStart = downloaded
            val startMs = System.currentTimeMillis()
            var lastReportMs = 0L
            body.byteStream().use { input ->
                FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= PROGRESS_INTERVAL_MS) {
                            lastReportMs = now
                            // Speed from this session's bytes only, not the resumed part.
                            val elapsedS = (now - startMs) / 1000f
                            val speed = if (elapsedS > 0f) (downloaded - sessionStart) / elapsedS else 0f
                            val remaining = if (speed > 0f) ((totalBytes - downloaded) / speed).toInt() else -1
                            onProgress(DownloadProgress.Downloading(
                                downloaded.toFloat() / totalBytes, downloaded, totalBytes, remaining))
                        }
                    }
                }
            }
            if (downloaded < totalBytes) return "The download ended early. Try again to resume it."
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
