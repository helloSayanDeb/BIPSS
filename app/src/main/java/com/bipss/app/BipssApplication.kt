package com.bipss.app

import android.app.Application
import androidx.work.Configuration
import com.bipss.app.di.appModule
import com.bipss.app.worker.EnvDataSyncWorker
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
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

        // Schedule periodic environmental data sync (Plan 8)
        EnvDataSyncWorker.schedule(this)
    }

    // Koin-managed WorkManager initializer
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(getKoin().get())
            .build()
}
