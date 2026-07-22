import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is read from a local, uncommitted `keystore.properties` (never from VCS, so no
// private keys are exposed — spec 014). When absent (local/CI beta builds), the release falls back to
// the standard debug keystore so `assembleRelease` still produces an installable APK for smoke-testing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "fm.rizx.player"

    // Compiled against Android 16 (API 36). Runs on API 34–37.
    // To target Android 17 (API 37) once its platform is installed via the SDK Manager,
    // bump compileSdk and targetSdk to 37 (and a matching AGP if Studio asks).
    compileSdk = 36

    defaultConfig {
        applicationId = "fm.rizx.player"
        minSdk = 34          // supports API 34, 35, 36 and 37
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real release key if configured; otherwise the debug key so beta builds stay installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.palette:palette-ktx:1.0.0") // dynamic accent from album art
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Media3 / ExoPlayer + MediaSessionService (Phase 8 playback, Phase 9 background service)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1") // HLS playback (e.g. SoundCloud plugin streams)
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    // Dependency injection (ADR 0005)
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Persistence (Phase 10): Room, DataStore, kotlinx.serialization
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Image loading (real cover art): Coil for Compose
    implementation("io.coil-kt:coil-compose:2.7.0")

    // HTTP for real providers (Phase 13, ADR 0006): Retrofit + OkHttp + kotlinx.serialization converter
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Native full-length YouTube audio (ADR 0014): NewPipeExtractor extracts stream URLs with no API key.
    // GPLv3 — compatible with this app's AGPL-3.0 (§13). Pulls jsoup + Rhino + nanojson transitively.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")

    // Embedded JS engine for the sandboxed plugin runtime (ADR 0014): QuickJS via quickjs-kt (Apache-2.0),
    // ~1.2 MiB native lib per ABI. Async binding + fetch bridge; QuickJS has no ambient capabilities.
    // Pinned to the last alpha built with Kotlin 2.0 — the 1.0.x stables use Kotlin 2.3, whose metadata
    // this project's 2.0.21 compiler + Hilt's metadata parser cannot read.
    implementation("io.github.dokar3:quickjs-kt:1.0.0-alpha13")

    // EVALUATION ONLY — audio tag writing (cover art / artist / album / year into the downloaded file).
    // Being checked for Android compatibility: the stock build reaches for java.awt / javax.imageio in its
    // artwork path, neither of which exists on Android. Remove if it doesn't hold up.
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
