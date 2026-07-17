package fm.rizx.player.ui.screens

/**
 * Third-party open-source dependency license report, surfaced in [LicensesScreen] (About →
 * Open-source licenses), satisfying the AGPL corresponding-source / attribution obligations
 * (spec 014). Mirrors `docs/THIRD_PARTY_LICENSES.md`; regenerate both from the resolved dependency
 * graph at release time (e.g. a Gradle license-reporting plugin) so they stay accurate.
 */
data class DependencyLicense(val name: String, val version: String, val license: String)

object LicenseData {

    /** Libraries bundled in the shipped APK. */
    val runtime: List<DependencyLicense> = listOf(
        DependencyLicense("Kotlin standard library", "2.0.21", "Apache-2.0"),
        DependencyLicense("KotlinX Coroutines", "1.8.x", "Apache-2.0"),
        DependencyLicense("KotlinX Serialization JSON", "1.7.3", "Apache-2.0"),
        DependencyLicense("AndroidX Core KTX", "1.15.0", "Apache-2.0"),
        DependencyLicense("AndroidX Palette", "1.0.0", "Apache-2.0"),
        DependencyLicense("AndroidX Activity Compose", "1.9.3", "Apache-2.0"),
        DependencyLicense("AndroidX Lifecycle", "2.8.7", "Apache-2.0"),
        DependencyLicense("Jetpack Compose (BOM)", "2024.12.01", "Apache-2.0"),
        DependencyLicense("Compose Material 3 + Icons", "BOM", "Apache-2.0"),
        DependencyLicense("Navigation Compose", "2.8.5", "Apache-2.0"),
        DependencyLicense("Media3 ExoPlayer / Session", "1.5.1", "Apache-2.0"),
        DependencyLicense("Hilt (Dagger)", "2.52", "Apache-2.0"),
        DependencyLicense("Hilt Navigation Compose", "1.2.0", "Apache-2.0"),
        DependencyLicense("Room", "2.6.1", "Apache-2.0"),
        DependencyLicense("DataStore Preferences", "1.1.1", "Apache-2.0"),
        DependencyLicense("Retrofit", "2.11.0", "Apache-2.0"),
        DependencyLicense("Retrofit KotlinX Serialization Converter", "2.11.0", "Apache-2.0"),
        DependencyLicense("OkHttp + Logging Interceptor", "4.12.0", "Apache-2.0"),
    )

    /** Test-only libraries (not shipped in the APK), listed for completeness. */
    val testOnly: List<DependencyLicense> = listOf(
        DependencyLicense("JUnit 4", "4.13.2", "EPL-1.0"),
        DependencyLicense("MockK", "1.13.13", "Apache-2.0"),
        DependencyLicense("Turbine", "1.1.0", "Apache-2.0"),
        DependencyLicense("OkHttp MockWebServer", "4.12.0", "Apache-2.0"),
    )
}
