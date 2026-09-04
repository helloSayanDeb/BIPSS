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
import com.bipss.app.data.environmental.CpcbAqiFetcher
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.data.environmental.ImdRssParser
import com.bipss.app.data.environmental.OpenMeteoFetcher
import com.bipss.app.data.environmental.OpenWeatherMapFetcher
import com.bipss.app.data.environmental.SachetCapFetcher
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
    single { UserPreferencesRepository(androidContext()) }

    // ── Health Connect (height/weight/steps only — see class doc) ───────────────
    single { HealthConnectRepository(androidContext()) }

    // ── Google Drive appDataFolder backup (see class doc re: OAuth client requirement) ──
    single { DriveBackupManager(androidContext(), get(), get(), get(), get()) }

    // ── Room ──────────────────────────────────────────────────────────────────
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "bipss_db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }

    // ── DAOs ──────────────────────────────────────────────────────────────────
    single { get<AppDatabase>().rawReadingDao() }
    single { get<AppDatabase>().baselineStatsDao() }
    single { get<AppDatabase>().emergencyContactDao() }
    single { get<AppDatabase>().sosEventDao() }

    // ── BLE ───────────────────────────────────────────────────────────────────
    single { BleGattManager(androidContext()) }

    // ── Sensor Pipeline ───────────────────────────────────────────────────────
    single { SensorRepository(get(), get(), get()) }

    // ── AI ────────────────────────────────────────────────────────────────────
    single { ChatbotEngine(androidContext(), get()) }
    single { ModelDownloadManager(androidContext(), get(), get()) }

    // ── Environmental ─────────────────────────────────────────────────────────
    single { ImdRssParser(get()) }
    single { CpcbAqiFetcher(get(), BuildConfig.CPCB_AQI_API_KEY) }
    single { OpenMeteoFetcher(get()) }
    single { OpenWeatherMapFetcher(get(), BuildConfig.OWM_API_KEY) }
    single { SachetCapFetcher(get()) }
    single { DisasterAlertRepository(get(), get(), get(), get(), get()) }

    // ── Domain ────────────────────────────────────────────────────────────────
    single { RiskScoreCalculator() }

    // ── SOS ───────────────────────────────────────────────────────────────────
    single { LocationHelper(androidContext()) }
    single { SmsSender(androidContext()) }

    // ── ViewModels ────────────────────────────────────────────────────────────
    viewModel { MainViewModel(get()) }

    viewModel {
        DashboardViewModel(get(), get(), get(), get(), androidContext())
    }

    viewModel {
        ChatDetailsViewModel(get(), get(), get())
    }

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
        SettingsViewModel(get(), get(), get(), get(), get(), get(), get())
    }

    viewModel {
        SosViewModel(get(), get(), get(), get(), get())
    }

    viewModel {
        ContactPickerViewModel(get(), androidContext())
    }
}
