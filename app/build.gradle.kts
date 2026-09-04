import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Load local.properties for API key injection
val localProps = Properties().apply {
    file("${rootDir}/local.properties")
        .takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace  = "com.bipss.app"
    compileSdk = 35

    defaultConfig {
        applicationId   = "com.bipss.app"
        minSdk          = 26
        targetSdk       = 35
        versionCode     = 2
        versionName     = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API keys injected via BuildConfig — never hardcoded in source
        buildConfigField("String", "CPCB_AQI_API_KEY",
            "\"${localProps.getProperty("CPCB_AQI_API_KEY", "")}\"")
        buildConfigField("String", "OWM_API_KEY",
            "\"${localProps.getProperty("OWM_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)

    // DataStore
    implementation(libs.datastore.preferences)

    // Health Connect
    implementation(libs.health.connect.client)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // OkHttp
    implementation(libs.okhttp)

    // MediaPipe GenAI
    implementation(libs.mediapipe.tasks.genai)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Location
    implementation(libs.play.services.location)

    // Google Sign-In (Drive appDataFolder backup)
    implementation(libs.play.services.auth)

    // Accompanist Permissions
    implementation(libs.accompanist.permissions)
}
