# Third-party dependency licenses — Rizx Player

Rizx Player bundles the open-source libraries below. This report is surfaced in-app via
**About → Open-source licenses** (`ui/screens/LicensesScreen.kt`, backed by `ui/screens/LicenseData.kt`).

> Regenerate at release time from the actual resolved dependency graph (e.g. a Gradle license-reporting
> plugin such as `com.jaredsburrows.license` or `com.github.jk1.dependency-license-report`) so the list
> stays accurate. This hand-curated report reflects `app/build.gradle.kts` as of version 0.1.0.

## App license

Rizx Player is a derived work of [nukeop/nuclear](https://github.com/nukeop/nuclear) and is licensed
under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. See the repository `LICENSE`.

## Runtime dependencies

| Library | Version | License |
|---|---|---|
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.0.21 | Apache-2.0 |
| KotlinX Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-*`) | 1.8.x | Apache-2.0 |
| KotlinX Serialization JSON (`org.jetbrains.kotlinx:kotlinx-serialization-json`) | 1.7.3 | Apache-2.0 |
| AndroidX Core KTX (`androidx.core:core-ktx`) | 1.15.0 | Apache-2.0 |
| AndroidX Palette (`androidx.palette:palette-ktx`) | 1.0.0 | Apache-2.0 |
| AndroidX Activity Compose (`androidx.activity:activity-compose`) | 1.9.3 | Apache-2.0 |
| AndroidX Lifecycle (runtime/viewmodel/compose) | 2.8.7 | Apache-2.0 |
| Jetpack Compose (BOM `androidx.compose:compose-bom`) | 2024.12.01 | Apache-2.0 |
| Compose Material 3 + Material Icons Extended | (BOM) | Apache-2.0 |
| Navigation Compose (`androidx.navigation:navigation-compose`) | 2.8.5 | Apache-2.0 |
| Media3 ExoPlayer / Common / Session (`androidx.media3:*`) | 1.5.1 | Apache-2.0 |
| Hilt (`com.google.dagger:hilt-android`) | 2.52 | Apache-2.0 |
| Hilt Navigation Compose (`androidx.hilt:hilt-navigation-compose`) | 1.2.0 | Apache-2.0 |
| Room (`androidx.room:room-runtime`, `room-ktx`) | 2.6.1 | Apache-2.0 |
| DataStore Preferences (`androidx.datastore:datastore-preferences`) | 1.1.1 | Apache-2.0 |
| Retrofit (`com.squareup.retrofit2:retrofit`) | 2.11.0 | Apache-2.0 |
| Retrofit KotlinX Serialization Converter (`com.squareup.retrofit2:converter-kotlinx-serialization`) | 2.11.0 | Apache-2.0 |
| OkHttp + Logging Interceptor (`com.squareup.okhttp3:*`) | 4.12.0 | Apache-2.0 |
| Coil Compose (`io.coil-kt:coil-compose`) | 2.7.0 | Apache-2.0 |

## Plugin system dependencies (ADR 0014 — hybrid runtime)

The app stays **AGPL-3.0**. The **GPLv3** NewPipeExtractor is combinable with AGPL-3.0 under §13 of both
licenses; the combined work is distributed under AGPL-3.0. Downloaded Nuclear plugins are separate *data*
(their own licenses), transpiled and run at runtime, not linked into the APK.

| Library | Version | License | Role |
|---|---|---|---|
| NewPipeExtractor (`com.github.teamnewpipe:NewPipeExtractor`) | v0.26.3 | **GPLv3** | Native full-length YouTube audio extraction |
| Mozilla Rhino (transitive of NewPipeExtractor) | (transitive) | MPL-2.0 | Runs YouTube's cipher/throttling JS |
| jsoup (transitive of NewPipeExtractor) | (transitive) | MIT | HTML parsing |
| nanojson (transitive of NewPipeExtractor) | (transitive) | BSD-2-Clause / MIT | JSON parsing |
| quickjs-kt (`io.github.dokar3:quickjs-kt`) *(Phase B)* | 1.0.5 | Apache-2.0 | Embedded JS engine (plugin runtime) |
| QuickJS (bundled native, via quickjs-kt) *(Phase B)* | — | MIT | JS engine core |
| Sucrase (vendored `assets/plugin-runtime/sucrase.min.js`) *(Phase B)* | — | MIT | On-device TypeScript→JS transpile |

## Bundled fonts

Variable TrueType fonts under `app/src/main/res/font/`, all licensed **SIL Open Font License 1.1**.

| Font | File | License | Role |
|---|---|---|---|
| Space Grotesk | `space_grotesk.ttf` | OFL-1.1 | Display / headings |
| Manrope | `manrope.ttf` | OFL-1.1 | Legacy body face (kept for revert) |
| Martian Mono | `martian_mono.ttf` | OFL-1.1 | Technical monospace — body / UI / labels (industrial restyle) |
| Doto | `doto.ttf` | OFL-1.1 | Dot-matrix numerals / short labels (Nothing-OS accent) |

## Test-only dependencies (not shipped in the APK)

| Library | Version | License |
|---|---|---|
| JUnit 4 (`junit:junit`) | 4.13.2 | Eclipse Public License 1.0 |
| MockK (`io.mockk:mockk`) | 1.13.13 | Apache-2.0 |
| Turbine (`app.cash.turbine:turbine`) | 1.1.0 | Apache-2.0 |
| OkHttp MockWebServer (`com.squareup.okhttp3:mockwebserver`) | 4.12.0 | Apache-2.0 |

## License texts

- **Apache License 2.0** — https://www.apache.org/licenses/LICENSE-2.0
- **Eclipse Public License 1.0** — https://www.eclipse.org/legal/epl-v10.html
- **AGPL-3.0** (this app) — https://www.gnu.org/licenses/agpl-3.0.html
- **GPLv3** — https://www.gnu.org/licenses/gpl-3.0.html
- **MPL-2.0** — https://www.mozilla.org/MPL/2.0/
- **MIT** — https://opensource.org/licenses/MIT
- **SIL Open Font License 1.1** — https://openfontlicense.org/

## Content provider

Search results and 30-second audio previews are retrieved at runtime from the **iTunes Search API**
(Apple Inc.), used under Apple's terms. No Apple code or assets are bundled in this app.
