package com.bipss.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val TAG = "ModelDownloadManager"

sealed class DownloadProgress {
    object Idle           : DownloadProgress()
    object Initializing   : DownloadProgress()
    data class Downloading(
        val fraction:                Float,
        val bytesDownloaded:         Long,
        val totalBytes:              Long,
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
        try {
            val request  = Request.Builder().url(MODEL_MANIFEST_URL).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
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
     */
    fun downloadModelIfNeeded(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Initializing)

        val manifest = fetchManifest()
        if (manifest == null) {
            emit(DownloadProgress.Failure("Could not reach manifest server. Check your connection."))
            return@flow
        }

        // Check if already up to date
        val installedVersion = prefsRepo.installedModelVersion.first()
        if (installedVersion == manifest.version && installedModelFile()?.exists() == true) {
            emit(DownloadProgress.AlreadyUpToDate)
            return@flow
        }

        val targetFile = File(modelDir, "bipss_model_${manifest.version}.bin")
        val startTime  = System.currentTimeMillis()

        // Download with progress
        withContext(Dispatchers.IO) {
            try {
                // Phase 11: AI backend config - Resumable downloads
                val existingLength = if (targetFile.exists()) targetFile.length() else 0L
                val requestBuilder = Request.Builder().url(manifest.downloadUrl)

                if (existingLength > 0 && existingLength < manifest.sizeBytes) {
                    requestBuilder.addHeader("Range", "bytes=$existingLength-")
                } else if (existingLength == manifest.sizeBytes) {
                    // Already downloaded the exact size, skip to verification
                    return@withContext
                } else if (existingLength > manifest.sizeBytes) {
                    // Corrupted/oversized, start fresh
                    targetFile.delete()
                }

                val request = requestBuilder.build()
                val response = okHttpClient.newCall(request).execute()

                // 206 Partial Content is expected for range requests, 200 for full.
                if (!response.isSuccessful || (response.code != 200 && response.code != 206)) {
                    emit(DownloadProgress.Failure("Download failed: HTTP ${response.code}"))
                    return@withContext
                }

                val body = response.body ?: run {
                    emit(DownloadProgress.Failure("Empty response"))
                    return@withContext
                }

                val totalBytes = manifest.sizeBytes
                val appendMode = response.code == 206
                var downloaded = if (appendMode) existingLength else 0L

                body.byteStream().use { input ->
                    // FileOutputStream(targetFile, true) enables append mode
                    FileOutputStream(targetFile, appendMode).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val fraction = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                            // Only calculate speed based on bytes downloaded *this session*
                            val sessionDownloaded = downloaded - (if (appendMode) existingLength else 0L)
                            val speed = if (elapsed > 0) sessionDownloaded / elapsed else 1f
                            val remaining = ((totalBytes - downloaded) / speed).toInt()
                            emit(DownloadProgress.Downloading(fraction, downloaded, totalBytes, remaining))
                        }
                    }
                }
            } catch (e: Exception) {
                emit(DownloadProgress.Failure("Download error: ${e.message}"))
                return@withContext
            }
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
