pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — only for NewPipeExtractor + its transitives (native full-length YouTube audio,
        // ADR 0014). Scoped so nothing else silently resolves from JitPack. Case-insensitive because the
        // group is published as both `com.github.teamnewpipe` (NewPipeExtractor) and
        // `com.github.TeamNewPipe` (transitive nanojson).
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.[Tt]eam[Nn]ew[Pp]ipe.*") }
        }
    }
}

rootProject.name = "Rizx"
include(":app")
// Generates the Baseline Profile consumed by :app. Never built by `assembleDebug` — it only runs on
// demand (`:app:generateReleaseBaselineProfile`) against a rooted AOSP managed device.
include(":baselineprofile")
