package fm.rizx.player.ui.screens

/**
 * Third-party open-source dependency report, surfaced in [LicensesScreen] (About → Open-source
 * licenses). This screen is the app's side of the acknowledgement obligations: Apache-2.0 §4 wants the
 * notices carried, and the GPL/LGPL/MIT/OFL components must be named to the person holding the build.
 *
 * It mirrors `docs/THIRD_PARTY_LICENSES.md` — **keep the two in step**, and re-check them whenever a
 * dependency is added: an omission here is the compliance gap, not the licence choice itself.
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
        DependencyLicense("Compose Material 3 + Icons Extended", "BOM", "Apache-2.0"),
        DependencyLicense("Navigation Compose", "2.8.5", "Apache-2.0"),
        DependencyLicense("Media3 ExoPlayer / HLS / Common / Session", "1.5.1", "Apache-2.0"),
        DependencyLicense("AndroidX WorkManager", "2.11.2", "Apache-2.0"),
        DependencyLicense("AndroidX Credentials (+ Play Services Auth)", "1.6.0", "Apache-2.0"),
        DependencyLicense("Google Identity (googleid)", "1.1.1", "Apache-2.0"),
        DependencyLicense("AndroidX Profile Installer", "1.4.1", "Apache-2.0"),
        DependencyLicense("Hilt (Dagger)", "2.52", "Apache-2.0"),
        DependencyLicense("Hilt Navigation Compose", "1.2.0", "Apache-2.0"),
        DependencyLicense("Room", "2.6.1", "Apache-2.0"),
        DependencyLicense("DataStore Preferences", "1.1.1", "Apache-2.0"),
        DependencyLicense("Retrofit", "2.11.0", "Apache-2.0"),
        DependencyLicense("Retrofit KotlinX Serialization Converter", "2.11.0", "Apache-2.0"),
        DependencyLicense("OkHttp + Logging Interceptor", "4.12.0", "Apache-2.0"),
        DependencyLicense("Coil", "2.7.0", "Apache-2.0"),
        DependencyLicense("ZXing Core (QR)", "3.5.3", "Apache-2.0"),
        DependencyLicense("quickjs-kt (plugin runtime)", "1.0.0-alpha13", "Apache-2.0"),
        DependencyLicense("QuickJS (native, via quickjs-kt)", "—", "MIT"),
        DependencyLicense("Sucrase (vendored, plugin transpiler)", "—", "MIT"),
        DependencyLicense("jsoup (via NewPipeExtractor)", "—", "MIT"),
        DependencyLicense("nanojson (via NewPipeExtractor)", "—", "BSD-2-Clause"),
        DependencyLicense("Mozilla Rhino (via NewPipeExtractor)", "—", "MPL-2.0"),
        DependencyLicense("Core library desugaring", "2.1.5", "GPL-2.0 + Classpath Exception"),
        DependencyLicense("jaudiotagger (embedded tags)", "3.0.1", "LGPL"),
        DependencyLicense("jump3r (LAME 3.98.4 Java port)", "1.0.5", "LGPL-2.1+"),
        DependencyLicense("NewPipeExtractor (YouTube / SoundCloud)", "v0.26.4", "GPL-3.0"),
    )

    /** Bundled typefaces — the OFL requires them to be named with their licence. */
    val fonts: List<DependencyLicense> = listOf(
        DependencyLicense("DM Sans", "variable", "OFL-1.1"),
        DependencyLicense("Martian Mono", "variable", "OFL-1.1"),
        DependencyLicense("Doto", "variable", "OFL-1.1"),
        DependencyLicense("Manrope", "variable", "OFL-1.1"),
    )

    /** Test-only libraries (not shipped in the APK), listed for completeness. */
    val testOnly: List<DependencyLicense> = listOf(
        DependencyLicense("JUnit 4", "4.13.2", "EPL-1.0"),
        DependencyLicense("MockK", "1.13.13", "Apache-2.0"),
        DependencyLicense("Turbine", "1.1.0", "Apache-2.0"),
        DependencyLicense("OkHttp MockWebServer", "4.12.0", "Apache-2.0"),
        DependencyLicense("KotlinX Coroutines Test", "1.8.1", "Apache-2.0"),
    )
}
