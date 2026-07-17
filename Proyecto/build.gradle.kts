// Top-level build file. Plugin versions are declared here and applied per-module.
// Toolchain chosen to support compileSdk 36 (Android 16). If Android Studio's AGP
// Upgrade Assistant proposes newer matching versions, accept them.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
