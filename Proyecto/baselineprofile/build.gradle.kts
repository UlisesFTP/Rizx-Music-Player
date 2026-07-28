/**
 * Baseline Profile generator for :app.
 *
 * Nothing here ships. Running `./gradlew :app:generateReleaseBaselineProfile` boots the managed device
 * below, exercises the app, and writes `app/src/release/generated/baselineProfile/baseline-prof.txt`,
 * which `androidx.profileinstaller` then installs on the user's device at first run.
 *
 * The device image must be a **rootable** one — profile collection needs `adb root`, which Play-flavoured
 * ("google_apis_playstore") images refuse. `google_apis` is rootable and is already present in this
 * machine's SDK, so nothing has to be downloaded.
 */
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "fm.rizx.player.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // `localDevices` is already typed as ManagedVirtualDevice, so this container is not polymorphic —
    // a `create<ManagedVirtualDevice>(...)` here fails at configuration time.
    testOptions.managedDevices.localDevices.create("pixel6Api34") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "google_apis"
    }
}

baselineProfile {
    // Runs against whatever is attached. **The device must be rootable** (`adb root` must succeed), so a
    // "google_apis" or "aosp" image — never "google_apis_playstore".
    //
    // The managed device above is declared but not used to *run*: on this Windows host Gradle cannot
    // boot it headless to take its snapshot (the emulator rejects the 'auto-no-window' GPU mode and
    // exits). The AVD it creates is perfectly good, though — boot it by hand and it shows up here:
    //
    //   set ANDROID_AVD_HOME=%USERPROFILE%\.android\avd\gradle-managed
    //   emulator -avd dev34_google_apis_x86_64_Pixel_6 -no-snapshot
    //   gradlew :app:generateReleaseBaselineProfile
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
}
