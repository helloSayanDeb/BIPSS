import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
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
        versionCode     = 3
        versionName     = "0.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API keys injected via BuildConfig — never hardcoded in source
        // Where the optional on-device model's manifest lives. Empty means no
        // model is offered: onboarding says so and makes no network request.
        buildConfigField("String", "MODEL_MANIFEST_URL",
            "\"${localProps.getProperty("MODEL_MANIFEST_URL", "")}\"")
        buildConfigField("String", "CPCB_AQI_API_KEY",
            "\"${localProps.getProperty("CPCB_AQI_API_KEY", "")}\"")
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

// Room schema export (enabled from DB v6) so hand-written migrations can be
// checked against the schema Room expects.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Location
    implementation(libs.play.services.location)

    // Google Sign-In (Drive appDataFolder backup)
    implementation(libs.play.services.auth)

    // Accompanist Permissions
    implementation(libs.accompanist.permissions)

    // Firebase
    implementation("com.google.firebase:firebase-firestore-ktx:25.1.4")
    implementation("com.google.firebase:firebase-auth-ktx:23.2.0")

    // Unit tests - the frame parser and scoring maths are pure Kotlin by design
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
