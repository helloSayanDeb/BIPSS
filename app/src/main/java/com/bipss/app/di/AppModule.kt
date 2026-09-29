package com.bipss.app.di

import androidx.room.Room
import com.bipss.app.BuildConfig
import com.bipss.app.MainViewModel
import com.bipss.app.ai.ChatbotEngine
import com.bipss.app.ai.ModelDownloadManager
import com.bipss.app.ble.BleGattManager
import com.bipss.app.data.SensorRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.backup.DriveBackupManager
import com.bipss.app.data.repository.HealthConnectRepository
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.migration.MIGRATION_1_2
import com.bipss.app.data.db.migration.MIGRATION_2_3
import com.bipss.app.data.db.migration.MIGRATION_3_4
import com.bipss.app.data.db.migration.MIGRATION_4_5
import com.bipss.app.data.db.migration.MIGRATION_5_6
import com.bipss.app.data.sharing.DataSharingRepository
import com.bipss.app.data.sharing.UserDataExporter
import com.bipss.app.data.environmental.CpcbAqiFetcher
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.data.environmental.ImdRssParser
import com.bipss.app.data.environmental.OpenMeteoFetcher
import com.bipss.app.data.environmental.HealthAdvisoryFetcher
import com.bipss.app.data.environmental.SachetJsonFetcher
import com.bipss.app.domain.RiskScoreCalculator
import com.bipss.app.sos.LocationHelper
import com.bipss.app.sos.SmsSender
import com.bipss.app.sos.SosViewModel
import com.bipss.app.ui.dashboard.ChatDetailsViewModel
import com.bipss.app.ui.dashboard.DashboardViewModel
import com.bipss.app.ui.onboarding.BleScanViewModel
import com.bipss.app.ui.onboarding.ModelDownloadViewModel
import com.bipss.app.ui.onboarding.OnboardingProfileViewModel
import com.bipss.app.ui.settings.ContactPickerViewModel
import com.bipss.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

val appModule = module {

    // ── Application-scoped CoroutineScope (for SensorRepository) ─────────────
    single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // ── Network ───────────────────────────────────────────────────────────────
    single {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ── DataStore ─────────────────────────────────────────────────────────────
    single { com.bipss.app.data.createPreferencesDataStore(androidContext()) }
    single { UserPreferencesRepository(androidContext(), get()) }

    // ── Health Connect (height/weight/steps only — see class doc) ───────────────
    single { HealthConnectRepository(androidContext()) }

    // ── Google Drive appDataFolder backup (see class doc re: OAuth client requirement) ──
    single { DriveBackupManager(androidContext(), get(), get(), get(), get()) }

    // ── Firebase (data sharing) ───────────────────────────────────────────────
    single { FirebaseFirestore.getInstance() }
    single { FirebaseAuth.getInstance() }

    // ── Data Sharing ──────────────────────────────────────────────────────────
    single { UserDataExporter(get(), get(), get(), get(), get(), get()) }
    single { DataSharingRepository(get(), get(), get()) }

    // ── Room ──────────────────────────────────────────────────────────────────
    single {
        // A separate file in simulation mode, so simulated data never mixes with real data.
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, com.bipss.app.SimulationMode.databaseName(androidContext()))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                com.bipss.app.data.db.migration.MIGRATION_6_7, com.bipss.app.data.db.migration.MIGRATION_7_8,
                com.bipss.app.data.db.migration.MIGRATION_8_9)
            .build()
    }

    // ── DAOs ──────────────────────────────────────────────────────────────────
    single { get<AppDatabase>().rawReadingDao() }
    single { get<AppDatabase>().minuteAggregateDao() }
    single { get<AppDatabase>().baselineStatsDao() }
    single { get<AppDatabase>().sleepSessionDao() }
    single { get<AppDatabase>().desaturationEventDao() }
    single { get<AppDatabase>().contextFeedbackDao() }
    single { get<AppDatabase>().emergencyContactDao() }
    single { get<AppDatabase>().sosEventDao() }
    single { get<AppDatabase>().waterLogDao() }
    single { get<AppDatabase>().signalEpochDao() }

    // ── BLE ───────────────────────────────────────────────────────────────────
    single { BleGattManager(androidContext()) }

    // ── Sensor Pipeline ───────────────────────────────────────────────────────
    single { com.bipss.app.data.SignalRepository(get(), get(), get()) }
    single { SensorRepository(get(), get(), get(), get(), get()) }
    single { com.bipss.app.data.ScoringRepository(get(), get()) }
    single { com.bipss.app.data.LegacyDataCleanup(get(), get()) }
    single { com.bipss.app.data.WaterRepository(get(), get()) }

    // ── Sleep ─────────────────────────────────────────────────────────────────
    single {
        com.bipss.app.data.SleepRepository(
            rawDao   = get(),
            sleepDao = get(),
            desatDao = get(),
            epochDao = get(),
            scoring  = get()
        )
    }

    // ── AI ────────────────────────────────────────────────────────────────────
    single { ChatbotEngine(androidContext(), get()) }
    single { ModelDownloadManager(androidContext(), get(), get()) }

    // ── Environmental ─────────────────────────────────────────────────────────
    single { SachetJsonFetcher(get()) }
    single { ImdRssParser(get()) }
    single { HealthAdvisoryFetcher(get()) }
    single { CpcbAqiFetcher(get(), BuildConfig.CPCB_AQI_API_KEY) }
    single { OpenMeteoFetcher(get()) }
    single {
        DisasterAlertRepository(
            sachetFetcher   = get(),
            imdParser       = get(),
            advisoryFetcher = get(),
            cpcbFetcher     = get(),
            openMeteo       = get(),
            locationHelper  = get(),
            prefsRepo       = get()
        )
    }

    // ── Domain ────────────────────────────────────────────────────────────────
    single { RiskScoreCalculator() }

    // ── SOS ───────────────────────────────────────────────────────────────────
    single { LocationHelper(androidContext()) }
    single { SmsSender(androidContext()) }

    // ── ViewModels ────────────────────────────────────────────────────────────
    viewModel { MainViewModel(get()) }

    viewModel {
        DashboardViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext())
    }

    viewModel { com.bipss.app.ui.recovery.RecoveryDetailViewModel(get()) }
    viewModel { com.bipss.app.ui.strain.StrainDetailViewModel(get()) }

    viewModel {
        ChatDetailsViewModel(
            chatbotEngine = get(),
            prefsRepo     = get(),
            sleepRepo     = get(),
            sensorRepo    = get(),
            envRepo       = get(),
            calculator    = get(),
            waterRepo     = get(),
            scoring       = get()
        )
    }

    viewModel { com.bipss.app.ui.sleep.SleepDetailViewModel(get()) }

    viewModel {
        OnboardingProfileViewModel(get(), get())
    }

    viewModel {
        BleScanViewModel(get(), get(), androidContext())
    }

    viewModel {
        ModelDownloadViewModel(get(), get(), get())
    }

    viewModel {
        SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), androidContext())
    }

    viewModel {
        SosViewModel(get(), get(), get(), get(), get())
    }

    viewModel {
        ContactPickerViewModel(get(), androidContext())
    }

    // Metric history - the metric key arrives as a nav argument.
    viewModel { params ->
        com.bipss.app.ui.metric.MetricDetailViewModel(
            metricType  = params.get(),
            sensorRepo  = get(),
            rawDao      = get(),
            aggDao      = get(),
            baselineDao = get()
        )
    }
}
