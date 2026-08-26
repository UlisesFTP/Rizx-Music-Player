# Building & running

The Android Studio project lives in **`Proyecto/`** (package `fm.rizx.player`). Everything below is run
from that directory.

## Requirements

| Tool | Version |
|---|---|
| Gradle JVM | **Java 21 recommended**; Gradle 8.12 supports 17–23 for this project; Java 25 is incompatible |
| Java target | Java/Kotlin bytecode 17 |
| Android SDK | `compileSdk 36` installed; the app **runs** on API 26+ (`minSdk 26`) |
| Android Studio | Any current release compatible with AGP 8.9.1; Quail 2026.1.3 verified locally |
| Build plugins | AGP 8.9.1 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Hilt 2.52 |
| Gradle | 8.12 via the committed wrapper (`./gradlew`) |

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

Android Studio uses `#GRADLE_LOCAL_JAVA_HOME` from `.idea/gradle.xml`. On this checkout the ignored
`.gradle/config.properties` should point it to the installed Java 21 JDK:

```properties
java.home=C\:\\Program Files\\Java\\jdk-21
```

Use **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** if your JDK is in a
different location. Keep machine-specific paths out of Git.

## Public runtime configuration

The optional account, sync and share-link features talk to a backend of your own. The build reads its
*public* coordinates from Gradle properties or environment variables of the same name:

| Property | What it is |
|---|---|
| `RIZX_SUPABASE_URL` | Base URL of the Supabase project (`https://<ref>.supabase.co`) |
| `RIZX_SUPABASE_PUBLISHABLE_KEY` | The project's **publishable** (`sb_publishable_…`) key — designed to ship in clients |
| `RIZX_GOOGLE_WEB_CLIENT_ID` | The Google OAuth **web** client id the native Google sign-in exchanges its token against |
| `RIZX_SHARE_BASE_URL` | Prefix printed on share links and QR codes (`https://<host>/s`); also cuts the App Links intent filter |
| `RIZX_TURNSTILE_CHALLENGE_URL` | Reserved for a CAPTCHA challenge page for guest (anonymous) share sessions. Compiled into `BuildConfig` but not consumed by 1.0.0: the app sends no CAPTCHA token, so the backend must allow anonymous sign-ins without one for guest links to work |

Put them in **`~/.gradle/gradle.properties`** (outside the repository) or export them in the
environment; the tracked `Proyecto/gradle.properties` must never carry them. Every value is public by
design — none of them grants more than an anonymous client already has — but they identify *your*
deployment, so they stay out of the repository. **Never** pass a service-role key, a Google client
secret, SMTP or CAPTCHA secrets: the app has no use for them and they must not exist in an APK.

When they are absent the build still succeeds: `BuildConfig` holds empty strings, the account screen
reports that no backend is configured, share links are unavailable, and the App Links filter points at
a reserved `.invalid` host. The backend itself (schema, RLS policies, the `rizx_sync` RPC, Edge
Functions) is a separate deployment and is not part of this repository.

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
reconstructible only from the migrations in `RizxDatabase.kt`. The instrumented suite covers 4 → 5,
5 → 6 and 6 → 7; schema 6 adds local-first account/sync bookkeeping and schema 7 the per-device
listening contributions, neither replacing existing media data.

Two artifacts are generated **into the source tree** by builds — worth knowing if you build from a
mirror/copy of the checkout (CI caches, synced build dirs): `app/schemas/` (any KSP build) and
`app/src/release/generated/baselineProfiles/` (only when running `generateReleaseBaselineProfile`).
Carry them back to the real checkout or they're lost on the next sync.

## Test

```bash
cd Proyecto
./gradlew testDebugUnitTest                                   # all unit tests (JVM, no device)
./gradlew testDebugUnitTest --tests "fm.rizx.player.data.provider.ProviderRegistryTest"   # a single class
./gradlew lintDebug                                           # static Android checks
```

Unit tests use JUnit4 · MockK · Turbine · OkHttp MockWebServer and run on the JVM (no emulator needed).
The 2026-08-25 repository snapshot runs **1,651 tests in 186 suites with zero failures or skips**.
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
│     │  │  ├─ lossless/  lyrics/  sync/  recognition/
│     │  ├─ playback/             # PlaybackService (MediaSessionService), stream resolver, effects
│     │  │  ├─ service/  cache/  canvas/  spatial/
│     │  ├─ widget/               # home screen widgets (RemoteViews, MediaController on tap)
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
- **“Incompatible Gradle JVM version” with Java 25** — select Java 21 for the Gradle JDK. Gradle 8.12
  cannot run on Java 25. The CLI check is `./gradlew --version`; both Launcher and Daemon JVM should
  report Java 21.
- **`Unable to load class com.google.devtools.ksp.gradle.KspTaskJvm`** — do not clear a healthy cache
  first. Confirm the project has the pinned compatible set: Gradle 8.12, AGP 8.9.1, Kotlin 2.0.21,
  KSP 2.0.21-1.0.28 and Hilt 2.52. This error occurs when Android Studio's Upgrade Assistant partially
  upgrades Gradle/Kotlin/KSP while leaving Hilt on the older API. Stop daemons with `./gradlew --stop`,
  restore those versions, then sync. Only re-download dependencies if an offline `./gradlew help`
  actually proves the cache is missing or corrupt.
