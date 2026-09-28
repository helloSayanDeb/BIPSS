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
            modules(appModule)
        }

        BipssNotifications.createChannels(this)

        // Periodic background work.
        EnvDataSyncWorker.schedule(this)
        // Rolls raw 1 Hz samples into minute aggregates so history views stay queryable.
        AggregationWorker.schedule(this)
        HydrationReminderWorker.schedule(this)
        SleepAnalysisWorker.schedule(this)
        DataSharingWorker.schedule(this)

        startUp()
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
            runCatching { getKoin().get<LegacyDataCleanup>().run() }
            runCatching {
                val prefs: UserPreferencesRepository = getKoin().get()
                val mac = prefs.pairedBandMac.first() ?: return@launch
                BleForegroundService.start(this@BipssApplication)
                val ble: BleGattManager = getKoin().get()
                ble.connectToDevice(mac, autoConnect = true)
            }
        }
    }

    // Koin-managed WorkManager initializer
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(getKoin().get())
            .build()
}
