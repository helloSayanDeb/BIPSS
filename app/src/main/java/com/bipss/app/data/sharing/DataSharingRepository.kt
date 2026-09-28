package com.bipss.app.data.sharing

import android.util.Log
import com.bipss.app.data.UserPreferencesRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Orchestrates the data sharing pipeline: checks opt-in, authenticates
 * anonymously with Firebase, and delegates the actual export to
 * [UserDataExporter].
 *
 * The sealed [UploadResult] ensures every caller knows exactly what happened
 * and can respond accordingly — no silent failures, no ambiguous states.
 */
class DataSharingRepository(
    private val auth:     FirebaseAuth,
    private val exporter: UserDataExporter,
    private val prefsRepo: UserPreferencesRepository
) {

    companion object {
        private const val TAG = "DataSharing"
    }

    sealed class UploadResult {
        object Success    : UploadResult()
        object UserOptedOut : UploadResult()
        data class Failed(val reason: String) : UploadResult()
    }

    /**
     * Main entry point, called by [DataSharingWorker] every hour.
     *
     * Returns a distinct result for each possible state:
     * - [UploadResult.UserOptedOut] — toggle is off, nothing happens
     * - [UploadResult.Success] — data uploaded successfully
     * - [UploadResult.Failed] — something went wrong (auth, network, Firestore)
     */
    suspend fun uploadIfEnabled(): UploadResult {
        runMaintenanceIfSignedIn()

        // Short-circuit: if user hasn't opted in, nothing new is sent.
        if (!prefsRepo.dataSharingEnabled.first()) {
            return UploadResult.UserOptedOut
        }

        return try {
            val uid = ensureAnonymousAuth()
                ?: return UploadResult.Failed("Anonymous auth failed")

            exporter.exportAndUpload(uid)
            Log.i(TAG, "Upload completed for uid=$uid")
            UploadResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            UploadResult.Failed(e.message ?: "unknown")
        }
    }

    /**
     * Clean-up of previously shared data. Uses the existing anonymous identity
     * only - it never signs in (and so never creates an identity) on its own.
     */
    private suspend fun runMaintenanceIfSignedIn() {
        val cachedUid = prefsRepo.sharingFirebaseUid.first() ?: return
        if (auth.currentUser?.uid != cachedUid) return
        runCatching { exporter.runMaintenance(cachedUid) }
            .onFailure { Log.w(TAG, "Shared-data maintenance failed; will retry", it) }
    }

    /**
     * Signs in anonymously if not already signed in. Returns the Firebase UID
     * on success, null on failure.
     *
     * The anonymous UID is cached in DataStore so the same Firestore user
     * document is reused across app restarts. A new UID is only created
     * after "Delete my data" clears the stored UID.
     */
    private suspend fun ensureAnonymousAuth(): String? {
        // Check if we already have a cached UID and the user is still signed in
        val cachedUid = prefsRepo.sharingFirebaseUid.first()
        val currentUser = auth.currentUser

        if (currentUser != null && cachedUid == currentUser.uid) {
            return currentUser.uid
        }

        // Sign in anonymously
        return try {
            val result = auth.signInAnonymously().await()
            val uid = result.user?.uid ?: return null
            prefsRepo.setSharingFirebaseUid(uid)
            Log.i(TAG, "Anonymous auth success: uid=$uid")
            uid
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous auth failed", e)
            null
        }
    }
}
