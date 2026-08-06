import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("androidx.baselineprofile")
}

// Release signing is read from a local, uncommitted `keystore.properties` (never from VCS, so no
// private keys are exposed — spec 014). When absent, `assembleRelease` FAILS at the packaging step
// (see the gate below the android block): a debug-signed release must never be distributed. For a
// minified debug-signed smoke build use `assembleReleaseTest` instead.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/**
 * A value from `keystore.properties`, trimmed. Trailing whitespace is invisible in an editor but is
 * kept verbatim by `Properties`, and a padded path or password fails with a message that blames the
 * keystore rather than the space.
 */
fun keystoreProp(name: String): String? = keystoreProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

android {
    namespace = "fm.rizx.player"

    // Compiled against Android 16 (API 36). Runs on API 26+ (Android 8.0): the code was written
    // against 34+, so every newer platform API is now guarded behind Build.VERSION checks —
    // docs/BUILD.md lists exactly what degrades on older devices. 26 is a hard floor: variable fonts
    // (ui/theme/Type.kt), adaptive icons, NotificationChannel and java.time/Base64 (ours, NewPipe's
    // and jaudiotagger's) all bottom out there.
    compileSdk = 36

    defaultConfig {
        applicationId = "fm.rizx.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProp("storeFile") != null) {
            create("release") {
                // rootProject.file, not file: a relative `storeFile` reads naturally as "next to
                // keystore.properties" (the Proyecto/ folder), whereas this script's own `file()`
                // would resolve it inside app/. Absolute paths are unaffected.
                storeFile = rootProject.file(keystoreProp("storeFile")!!)
                storePassword = keystoreProp("storePassword")
                keyAlias = keystoreProp("keyAlias")
                keyPassword = keystoreProp("keyPassword")
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
            // The debug-key fallback exists ONLY so configuration/sync succeeds on machines without
            // a keystore; actually PACKAGING a release without the real key is blocked further down.
            // A debug-signed APK that reached users could never be updated with the real signature.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // What `release` silently was before that gate: minified, non-debuggable, signed with the
        // debug key. For smoke-testing the real build on a device. Same applicationId on purpose —
        // it updates an existing debug-signed release install in place.
        create("releaseTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Backports newer JDK APIs for API < 33 devices. Not optional at minSdk 26: NewPipe's
        // Utils.encodeUrlUtf8 calls URLEncoder.encode(String, Charset) — a Java 10 API that only
        // reached Android in 33 — and NoSuchMethodError-crashed the process on Android 9 the moment
        // a YouTube stream resolved. Lint can't see inside third-party jars; this is the safety net.
        isCoreLibraryDesugaringEnabled = true
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
    testOptions {
        unitTests {
            // Android's stub `android.jar` throws on every call by default, which makes a single
            // `Log.w` in an otherwise pure class untestable — the exception escapes the coroutine and
            // the assertion that follows never runs. Returning defaults instead lets a class keep its
            // diagnostics without dragging Robolectric in for them.
            isReturnDefaultValues = true
        }
    }
}

// `assembleRelease`/`bundleRelease` must carry the real signature. Failing at the packaging task —
// not at configuration — keeps sync, lint and compilation working without a keystore, and (unlike a
// `taskGraph.whenReady` hook) stays correct if the configuration cache is ever enabled. Exact
// task-name match on purpose: `packageReleaseTest` must stay exempt.
val hasReleaseKeystore = keystoreProp("storeFile") != null
val keystoreHint = keystorePropsFile.absolutePath
tasks.configureEach {
    if (name == "packageRelease" || name == "packageReleaseBundle") {
        doFirst {
            if (!hasReleaseKeystore) throw GradleException(
                "Release build blocked: no release keystore is configured ($keystoreHint not found) " +
                    "and a debug-signed release must never be distributed — it could not be updated " +
                    "with the real key later. Create one following docs/BUILD.md § Release signing, " +
                    "or run `assembleReleaseTest` for a minified debug-signed smoke build."
            )
        }
    }
}

// Room writes one schema JSON per database version here; `app/schemas/` is committed so future
// migrations can be reviewed and tested against the real history (docs/BUILD.md § Room schemas).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// `MigrationTestHelper` reads the exported schemas off the *device*, so they have to travel with the
// instrumented APK. Nothing here reaches the app itself.
android.sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // See compileOptions.isCoreLibraryDesugaringEnabled — required by NewPipeExtractor below API 33.
    // The _nio flavor specifically: the base artifact does NOT retarget URLEncoder.encode(String,
    // Charset) (verified by crashing on it); _nio is also what the NewPipe app itself ships with.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

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

    // Installs the Baseline Profile generated by :baselineprofile at first run, so the startup and
    // Home-scroll paths are AOT-compiled instead of interpreted. Release builds only — a debuggable
    // build is never AOT-compiled from a profile.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    // HTTP for real providers (Phase 13, ADR 0006): Retrofit + OkHttp + kotlinx.serialization converter
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Native full-length YouTube audio (ADR 0014): NewPipeExtractor extracts stream URLs with no API key.
    // GPLv3 — compatible with this app's AGPL-3.0 (§13). Pulls jsoup + Rhino + nanojson transitively.
    // v0.26.4 fixes "[YouTube] Fix fetching playlists continuations" (#1518) — the bug that made every
    // YouTube playlist longer than ~100 tracks import as only its first page.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.4")

    // Embedded JS engine for the sandboxed plugin runtime (ADR 0014): QuickJS via quickjs-kt (Apache-2.0),
    // ~1.2 MiB native lib per ABI. Async binding + fetch bridge; QuickJS has no ambient capabilities.
    // Pinned to the last alpha built with Kotlin 2.0 — the 1.0.x stables use Kotlin 2.3, whose metadata
    // this project's 2.0.21 compiler + Hilt's metadata parser cannot read.
    implementation("io.github.dokar3:quickjs-kt:1.0.0-alpha13")

    // EVALUATION ONLY — audio tag writing (cover art / artist / album / year into the downloaded file).
    // Being checked for Android compatibility: the stock build reaches for java.awt / javax.imageio in its
    // artwork path, neither of which exists on Android. Remove if it doesn't hold up.
    implementation("net.jthink:jaudiotagger:3.0.1")

    // MP3 encoding for the "MP3" download format (LAME 3.98.4 ported to pure Java; LGPL, Maven Central).
    // Android has no MP3 *encoder* (MediaCodec only decodes), so this can't be done with the framework.
    // Pure JVM on purpose: no NDK, no per-ABI .so, and the encode path is unit-testable. Its convenience
    // `lowlevel.LameEncoder` touches javax.sound (absent on Android) — our wrapper drives the `mp3.*`
    // engine directly and never loads that class.
    implementation("de.sciss:jump3r:1.0.5")

    // Instrumented UI tests. The karaoke lyrics view is the one screen whose correctness is a *timing*
    // question — active line, sweep, auto-scroll — and none of that can be asserted on the JVM. These
    // run only with a device attached (`./gradlew connectedDebugAndroidTest`); the JVM suite is unaffected.
    // The runner comes transitively from test.ext:junit, and `ui-test-manifest` (already a
    // debugImplementation, above) is what supplies the empty ComponentActivity they compose into.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // Migration testing. `MigrationTestHelper` opens a database at an old version from the exported
    // schema, applies the real `Migration`, and validates the result against the new schema — which is
    // only possible now that schemas are exported, so 4 → 5 is the first migration this project can
    // actually prove. Instrumented-only, so it never enters the APK or the JVM suite.
    androidTestImplementation("androidx.room:room-testing:2.6.1")

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
