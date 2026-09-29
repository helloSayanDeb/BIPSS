package com.bipss.app

import android.app.Application
import androidx.work.Configuration
import com.bipss.app.di.appModule
import com.bipss.app.notifications.BipssNotifications
import com.bipss.app.data.sharing.DataSharingWorker
import com.bipss.app.worker.AggregationWorker
import com.bipss.app.worker.EnvDataSyncWorker
import com.bipss.app.worker.HydrationReminderWorker
import com.bipss.app.worker.SleepAnalysisWorker
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import com.bipss.app.ble.BleForegroundService
import com.bipss.app.ble.BleGattManager
import com.bipss.app.data.LegacyDataCleanup
import com.bipss.app.data.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.core.component.KoinComponent
import org.koin.core.logger.Level

class BipssApplication : Application(), Configuration.Provider, KoinComponent {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@BipssApplication)
            workManagerFactory()
            modules(listOf(appModule) + com.bipss.app.debug.debugModules)
        }

        BipssNotifications.createChannels(this)
        schedulePeriodicWork(this)
        startUp()
    }

    companion object {
        /**
         * The app's periodic background work. Idempotent (unique work), so it
         * is also called after "Delete my data" cancels everything mid-process.
         */
        fun schedulePeriodicWork(context: android.content.Context) {
            EnvDataSyncWorker.schedule(context)
            // Rolls raw 1 Hz samples into minute aggregates so history views stay queryable.
            AggregationWorker.schedule(context)
            HydrationReminderWorker.schedule(context)
            SleepAnalysisWorker.schedule(context)
            DataSharingWorker.schedule(context)
        }
    }

    /**
     * Repairs data left by removed code, then reconnects a previously paired
     * band. Ordered so no new band reading lands before a pending wipe runs;
     * the background workers that touch the same tables wait on
     * [LegacyDataCleanup.awaitReady] for the same reason.
     *
     * Without the reconnect the app only ever connects during onboarding, so a
     * phone restart would silently end data collection until the user noticed
     * and re-paired.
     */
    private fun startUp() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (SimulationMode.isActive(this@BipssApplication)) seedSimulationProfile()
            runCatching { getKoin().get<LegacyDataCleanup>().run() }
            // Persist band readings for the life of the process, not just
            // while a screen happens to be open.
            runCatching { getKoin().get<com.bipss.app.data.SensorRepository>().start() }
            runCatching { getKoin().get<com.bipss.app.data.WaterRepository>().migrateLegacyCounter() }
            // Shared data uploaded while Demo Mode was on is purged from the
            // cloud too; local sharing consent is left as the user set it.
            // A cloud deletion the user asked for must finish even if the work
            // was lost (cancelled by a later wipe, or the app force-stopped).
            runCatching {
                val prefs: UserPreferencesRepository = getKoin().get()
                val status = prefs.cloudDeletionStatus.first()
                if (prefs.pendingCloudDeletionUid.first() != null &&
                    (status == com.bipss.app.data.sharing.CloudDeletionStatus.PENDING ||
                        status == com.bipss.app.data.sharing.CloudDeletionStatus.IN_PROGRESS)
                ) {
                    com.bipss.app.data.sharing.CloudDeletionWorker.enqueue(this@BipssApplication)
                }
            }
            runCatching {
                val prefs: UserPreferencesRepository = getKoin().get()
                val uid = prefs.sharingFirebaseUid.first()
                if (prefs.remotePurgeRequested.first() && uid != null && prefs.pendingCloudDeletionUid.first() == null) {
                    prefs.setPendingCloudDeletionUid(uid)
                    com.bipss.app.data.sharing.CloudDeletionWorker.enqueue(
                        this@BipssApplication, com.bipss.app.data.sharing.CloudDeletionWorker.MODE_PURGE_KEEP_LOCAL
                    )
                }
            }
            runCatching {
                val prefs: UserPreferencesRepository = getKoin().get()
                val mac = prefs.pairedBandMac.first() ?: return@launch
                BleForegroundService.start(this@BipssApplication)
                val ble: BleGattManager = getKoin().get()
                ble.connectToDevice(mac, autoConnect = true)
            }
        }
    }

    /**
     * Simulation mode only, and only when asked for with skip_onboarding: a
     * placeholder profile in the separate simulation preferences, so a
     * scenario can be shown without walking through onboarding. Never touches
     * the real preferences file.
     */
    private suspend fun seedSimulationProfile() {
        if (!SimulationMode.wantsSeededProfile(this)) return
        val prefs: UserPreferencesRepository = getKoin().get()
        if (prefs.hasCompletedOnboarding.first()) return
        prefs.saveUserProfile(com.bipss.app.data.UserProfile(name = "Simulated user", age = 30, heightCm = 170f, weightKg = 65f,
            gender = com.bipss.app.data.Gender.UNSPECIFIED))
        prefs.setOnboardingComplete()
    }

    // Koin-managed WorkManager initializer
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(getKoin().get())
            .build()
}
