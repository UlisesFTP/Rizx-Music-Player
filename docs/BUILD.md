# Building & running

The Android Studio project lives in **`Proyecto/`** (package `fm.rizx.player`). Everything below is run
from that directory.

## Requirements

| Tool | Version |
|---|---|
| JDK | 17+ (the project compiles against Java 17) |
| Android SDK | `compileSdk 36` installed; the app **runs** on API 26+ (`minSdk 26`) |
| Android Studio | Meerkat 2024.3+ (ships AGP 8.9) — optional if you use the CLI |
| Gradle | via the committed wrapper (`./gradlew`) |

The app was written API-first against modern Android; every platform API newer than 26 sits behind a
`Build.VERSION` check, so devices from Android 8.0 up run the same build. What degrades where (Opus
downloads, splash, per-app language, legacy storage permissions…) is listed in
[FEATURES.md § Device compatibility](FEATURES.md#device-compatibility). **Core library desugaring** is
enabled and load-bearing: third-party jars can call JDK APIs newer than the device's runtime — lint
cannot see into compiled dependencies — and NewPipeExtractor in particular requires desugaring below
API 33 (`URLEncoder.encode(String, Charset)`).

## Local configuration

Create `Proyecto/local.properties` pointing at your SDK (Android Studio writes this for you):

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

`local.properties` is **git-ignored** — never commit it.

## Build

```bash
cd Proyecto
./gradlew assembleDebug          # debug APK  → app/build/outputs/apk/debug/
./gradlew assembleReleaseTest    # minified smoke build, signed with the debug key
./gradlew assembleRelease        # real release — requires a configured keystore (fails without one)
```

Three build types:

- **`debug`** — debuggable, id `fm.rizx.player.debug` (suffixed) so it can sit alongside a release
  install.
- **`releaseTest`** — `initWith(release)`: minified + shrunk + non-debuggable, but signed with the
  standard **debug key**. For smoke-testing the real R8 build on a device. Never distribute it.
- **`release`** — the distributable build. Packaging **fails on purpose** when no real keystore is
  configured: a debug-signed APK that reached users could never be updated with the real signature later.

### Release signing

Release signing reads from an **uncommitted** `Proyecto/keystore.properties`:

```properties
storeFile=rizx-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

A relative `storeFile` is resolved from the `Proyecto/` folder — i.e. next to `keystore.properties`
itself; an absolute path works too.

When the file is absent, `assembleRelease` / `bundleRelease` stop at the packaging task with a message
pointing here — use `assembleReleaseTest` for keystore-less local builds. `keystore.properties` and all
`*.jks`/`*.keystore` files are **git-ignored** — signing keys are never committed.

Creating a release keystore (one-time):

```bash
keytool -genkeypair -v \
  -keystore rizx-release.jks -storetype PKCS12 \
  -alias rizx -keyalg RSA -keysize 4096 -validity 10950
```

(`keytool` ships with the JDK; on Windows it's `"%JAVA_HOME%\bin\keytool"`.) Point
`keystore.properties` at the result. **Back up the keystore file and both passwords somewhere safe** —
losing them means never being able to update the published app under the same identity.

> **Signature continuity:** builds distributed before this policy existed were debug-signed. A properly
> signed release cannot update those installs — Android blocks cross-signature updates by design, so
> such devices must uninstall once.

## Room schemas

`RizxDatabase` exports one schema JSON per database version into **`app/schemas/`** (committed). The
policy: every `version` bump ships its `Migration`, the newly exported schema JSON, **and** a
`MigrationTestHelper` case in `RizxMigrationTest` — in the same commit, so migrations are reviewable and
provable against the real history. The export starts at version 4; versions 1–3 predate it and are
reconstructible only from the migrations in `RizxDatabase.kt`, so 4 → 5 is the first testable one.

Two artifacts are generated **into the source tree** by builds — worth knowing if you build from a
mirror/copy of the checkout (CI caches, synced build dirs): `app/schemas/` (any KSP build) and
`app/src/release/generated/baselineProfiles/` (only when running `generateReleaseBaselineProfile`).
Carry them back to the real checkout or they're lost on the next sync.

## Test

```bash
cd Proyecto
./gradlew testDebugUnitTest                                   # all unit tests (JVM, no device)
./gradlew testDebugUnitTest --tests "fm.rizx.player.data.provider.ProviderRegistryTest"   # a single class
```

Unit tests use JUnit4 · MockK · Turbine · OkHttp MockWebServer and run on the JVM (no emulator needed).
Instrumented tests run via `./gradlew connectedDebugAndroidTest` (device/emulator required): the
karaoke-lyrics timing screen, and the **Room migration tests** (`RizxMigrationTest`), which open a
database at the previous version from the exported schema, apply the real `Migration`, and check that
nothing was lost. Those read the schemas off the device, which is why `app/schemas/` is added to the
`androidTest` assets in `app/build.gradle.kts`.

## Run

Open `Proyecto/` in Android Studio and run the **app** configuration on a device or emulator (API 26+),
or install a built APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project structure

```
Proyecto/
├─ app/
│  ├─ build.gradle.kts            # module config, dependencies, build types, signing gate
│  ├─ proguard-rules.pro
│  ├─ schemas/                    # exported Room schema JSONs (committed)
│  └─ src/
│     ├─ main/java/fm/rizx/player/
│     │  ├─ core/                 # error, network, cache, DI modules, formatting
│     │  ├─ domain/               # models, provider/repository contracts, use cases (NO Android)
│     │  │  ├─ model/  provider/  repository/  usecase/  playback/
│     │  ├─ data/                 # providers, remote clients, local stores, mappers, repositories
│     │  │  ├─ provider/  remote/  repository/  local/  download/  plugin/  search/  canvas/
│     │  │  ├─ lossless/  lyrics/
│     │  ├─ playback/             # PlaybackService (MediaSessionService), stream resolver, effects
│     │  │  ├─ service/  cache/  canvas/
│     │  └─ ui/                   # Compose screens, theme, navigation, components
│     │     ├─ screens/  components/  theme/  navigation/  player/  home/  library/ …
│     ├─ main/assets/plugins/     # git-ignored on purpose — see below
│     └─ test/java/fm/rizx/player/ # JVM unit tests
├─ baselineprofile/               # com.android.test module that generates the startup profile
├─ build.gradle.kts · settings.gradle.kts
├─ gradle/ · gradlew · gradlew.bat
└─ gradle.properties
```

**About `assets/plugins/`:** the repository deliberately distributes no plugin archives — a plugin's
whole content is a pointer to somewhere, and this repo stays a generic plugin host rather than a
distributor of anybody's index. A fresh clone therefore builds an app with **zero bundled plugins** (and
no plugin section in the UI); everything else works fully. See
[PROVIDERS.md](PROVIDERS.md#the-plugin-runtime) for the runtime itself.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the layering rules and why the dependency direction matters.

## Troubleshooting

- **SDK not found** — check `local.properties` `sdk.dir`.
- **`assembleRelease` fails with "Release build blocked"** — expected without a keystore; that is the
  signing gate. Configure `keystore.properties` (above) or build `assembleReleaseTest`.
- **JitPack dependency (NewPipeExtractor) fails to resolve** — the JitPack repository is scoped in
  `settings.gradle.kts` to `com.github.[Tt]eam[Nn]ew[Pp]ipe*`; a network hiccup on first resolve usually
  fixes itself on retry.
- **Wrong JDK** — ensure Gradle uses JDK 17+ (Android Studio: *Settings → Build Tools → Gradle → Gradle
  JDK*).
