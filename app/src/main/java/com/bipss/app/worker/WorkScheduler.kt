package com.bipss.app.worker

import android.content.Context
import com.bipss.app.SimulationMode
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.db.dao.EnvSnapshotDao
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.data.sharing.DataSharingWorker
import com.bipss.app.sos.LocationHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides which background jobs should exist and makes WorkManager match.
 *
 * Called at app start, whenever the app comes back to the foreground, and
 * wherever a condition changes (a location grant, pairing or forgetting a
 * band, switching sharing on or off, the data wipe). Each job runs only
 * while it has something to do:
 *
 *  - Environment sync, every 15 minutes: only while location permission is
 *    granted (fine or approximate - [LocationHelper.hasAnyPermission] is the
 *    single gate). When permission goes, the job is cancelled and everything
 *    derived from location is forgotten: the last position, the cached
 *    environment and the snapshot history.
 *  - Aggregation and sleep analysis: only with a paired band (or the
 *    simulator); without one there is nothing to process.
 *  - Data sharing: only while the user has opted in.
 *  - Hydration reminders: always scheduled; the job itself checks the
 *    notification permission and waits for a first logged drink.
 */
class WorkScheduler(
    private val context:        Context,
    private val prefs:          UserPreferencesRepository,
    private val locationHelper: LocationHelper,
    private val envRepo:        DisasterAlertRepository,
    private val envSnapshotDao: EnvSnapshotDao
) {
    private val mutex = Mutex()

    data class Plan(val envSync: Boolean, val bandJobs: Boolean, val sharing: Boolean)

    suspend fun reconcile() = mutex.withLock {
        val plan = Plan(
            envSync  = locationHelper.hasAnyPermission(),
            bandJobs = prefs.pairedBandMac.first() != null || SimulationMode.isActive(context),
            sharing  = prefs.dataSharingEnabled.first()
        )
        val lastSeen = prefs.locationGrantedLastSeen.first()

        // State first, WorkManager after: the sync job itself calls this when
        // it finds the permission gone, and cancelling it cancels the caller.
        if (!plan.envSync && lastSeen != false) {
            // Once, when the permission goes (or on the first look after an
            // update); after that there is nothing left to clear.
            envRepo.clearLocationDerivedData()
            envSnapshotDao.clear()
        }
        // A grant made since the last look (in Android's settings, or from the
        // Health tab) gets the same calibrate-fetch-verify setup as onboarding.
        if (isNewGrant(lastSeen, plan.envSync)) prefs.setLocationSetupPending(true)
        if (lastSeen != plan.envSync) prefs.setLocationGrantedLastSeen(plan.envSync)

        if (plan.bandJobs) {
            AggregationWorker.schedule(context)
            SleepAnalysisWorker.schedule(context)
        } else {
            AggregationWorker.cancel(context)
            SleepAnalysisWorker.cancel(context)
        }
        if (plan.sharing) DataSharingWorker.schedule(context) else DataSharingWorker.cancel(context)
        HydrationReminderWorker.schedule(context)
        // UPDATE keeps an existing schedule's timing, so calling this on every
        // return to the foreground does not keep pushing the next run back.
        if (plan.envSync) EnvDataSyncWorker.schedule(context) else EnvDataSyncWorker.cancel(context)
    }

    companion object {
        /**
         * Only a change seen from "not granted" counts. Unknown (first run of
         * this version) is not a change: an existing user who always had
         * location is not sent through setup.
         */
        fun isNewGrant(lastSeen: Boolean?, grantedNow: Boolean): Boolean = grantedNow && lastSeen == false
    }
}
