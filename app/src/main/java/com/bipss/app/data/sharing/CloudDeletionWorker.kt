package com.bipss.app.data.sharing

import android.content.Context
import android.util.Log
import androidx.work.*
import com.bipss.app.data.UserPreferencesRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Deletes everything this install ever shared with Firestore, then the
 * anonymous account itself.
 *
 * "Delete my data" used to write a timestamp, show a seven-day countdown and
 * never touch the cloud at all. This does the real thing:
 *  1. every document in each subcollection of `users/{uid}`, in batches;
 *  2. the `users/{uid}` document;
 *  3. the anonymous Firebase user, then sign-out - so opting in again later
 *     starts a genuinely new identity instead of silently reusing this one.
 *
 * It runs as unique one-time work with a network constraint, so it survives
 * the app being closed and finishes on its own once the phone is online.
 * Deletion is naturally resumable (deleted documents stay deleted), and each
 * run stops before WorkManager's ten-minute limit and asks to be re-run if
 * there is more left. Progress and the final outcome are persisted so the
 * Settings screen can report exactly what happened.
 *
 * Also used, with [MODE_PURGE_KEEP_LOCAL], to purge shared data known to be
 * contaminated (uploaded while Demo Mode was on) without touching local data.
 *
 * Requires Firestore security rules that let a user delete their own tree:
 * `allow delete: if request.auth.uid == userId` on `users/{userId}` and
 * `users/{userId}/{document=**}`.
 */
class CloudDeletionWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val prefs:     UserPreferencesRepository by inject()
    private val firestore: FirebaseFirestore by inject()
    private val auth:      FirebaseAuth by inject()

    override suspend fun doWork(): Result {
        val uid = prefs.pendingCloudDeletionUid.first() ?: return Result.success()
        val mode = inputData.getString(KEY_MODE) ?: MODE_DELETE_ALL
        val started = System.currentTimeMillis()

        // Only the identity that uploaded the data may delete it. If that
        // anonymous session is gone the account cannot be recovered, and
        // saying so is the only honest outcome.
        if (auth.currentUser?.uid != uid) {
            Log.w(TAG, "Signed-in identity no longer matches $uid - cannot delete its data")
            prefs.setCloudDeletionStatus(CloudDeletionStatus.IDENTITY_LOST, prefs.cloudDeletionCount.first())
            prefs.setPendingCloudDeletionUid(null)
            return Result.success()
        }

        prefs.setCloudDeletionStatus(CloudDeletionStatus.IN_PROGRESS, prefs.cloudDeletionCount.first())
        return try {
            val userRef = firestore.collection("users").document(uid)
            var deleted = prefs.cloudDeletionCount.first()
            for (name in UserDataExporter.SUBCOLLECTIONS) {
                while (true) {
                    if (System.currentTimeMillis() - started > RUN_BUDGET_MS) {
                        Log.i(TAG, "Run budget used after $deleted documents - continuing in a new run")
                        return Result.retry()
                    }
                    // SERVER, never the default: offline, the default source
                    // answers from the local cache, whose "empty" would let
                    // the loop finish and report DONE with server documents left.
                    val page = userRef.collection(name).limit(BATCH).get(Source.SERVER).await()
                    if (page.isEmpty) break
                    val batch = firestore.batch()
                    page.documents.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                    deleted += page.size()
                    prefs.setCloudDeletionStatus(CloudDeletionStatus.IN_PROGRESS, deleted)
                    setProgress(workDataOf(KEY_DELETED to deleted))
                }
            }
            userRef.delete().await()
            deleted += 1

            // The account itself. Deleting an anonymous user can require a
            // recent sign-in; either way the session is ended, so a later
            // opt-in creates a new identity.
            val accountRemoved = runCatching { auth.currentUser?.delete()?.await(); true }
                .onFailure { Log.w(TAG, "Anonymous account delete failed (${it.message}); signing out") }
                .getOrDefault(false)
            auth.signOut()

            prefs.onCloudDeletionFinished(
                deleted, keepLocalSharingState = mode == MODE_PURGE_KEEP_LOCAL, accountRemoved = accountRemoved
            )
            Log.i(TAG, "Deleted $deleted shared document(s) for this install")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cloud deletion failed: ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else {
                prefs.setCloudDeletionStatus(CloudDeletionStatus.FAILED, prefs.cloudDeletionCount.first())
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "CloudDeletionWorker"
        const val WORK_NAME = "cloud_data_deletion"
        const val KEY_DELETED = "deleted"
        private const val KEY_MODE = "mode"
        const val MODE_DELETE_ALL = "delete_all"
        const val MODE_PURGE_KEEP_LOCAL = "purge_keep_local"

        private const val BATCH = 400L
        private const val RUN_BUDGET_MS = 8 * 60_000L
        private const val MAX_ATTEMPTS = 8

        /**
         * @param replace true for a deletion the user just asked for: it takes
         *   over any queued run (for example a contamination purge) instead of
         *   being dropped behind it.
         */
        fun enqueue(context: Context, mode: String = MODE_DELETE_ALL, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<CloudDeletionWorker>()
                .setInputData(workDataOf(KEY_MODE to mode))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Outcome of the most recent cloud deletion, persisted so it can be shown after the fact. */
enum class CloudDeletionStatus { NONE, PENDING, IN_PROGRESS, DONE, FAILED, IDENTITY_LOST }
