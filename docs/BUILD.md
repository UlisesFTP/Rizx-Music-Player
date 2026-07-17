# Building & running

The Android Studio project lives in **`Proyecto/`** (package `fm.rizx.player`). Everything below is run
from that directory.

## Requirements

| Tool | Version |
|---|---|
| JDK | 17+ (the project compiles against Java 17) |
| Android SDK | API 34–36 installed (`compileSdk 36`, `minSdk 34`) |
| Android Studio | Meerkat 2024.3+ (ships AGP 8.9) — optional if you use the CLI |
| Gradle | via the committed wrapper (`./gradlew`) |

`minSdk 34` means the app targets Android 14+ and can use modern, guard-free APIs (e.g.
`READ_MEDIA_AUDIO`, scoped storage) without legacy fallbacks.

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
./gradlew assembleRelease        # release APK (minified + shrunk)
```

The **debug** build id is `fm.rizx.player.debug` (suffixed) so it can sit alongside a release install.

### Release signing

Release signing reads from an **uncommitted** `Proyecto/keystore.properties`:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

When it's absent (local/CI beta builds), the release build falls back to the standard debug keystore so
`assembleRelease` still produces an installable APK. `keystore.properties` and all `*.jks`/`*.keystore`
files are **git-ignored** — signing keys are never committed.

## Test

```bash
cd Proyecto
./gradlew testDebugUnitTest                                   # all unit tests (JVM, no device)
./gradlew testDebugUnitTest --tests "fm.rizx.player.data.provider.ProviderRegistryTest"   # a single class
```

Unit tests use JUnit4 · MockK · Turbine · OkHttp MockWebServer and run on the JVM (no emulator needed).
Instrumented tests, if/when added, run via `./gradlew connectedDebugAndroidTest` (device/emulator
required).

## Run

Open `Proyecto/` in Android Studio and run the **app** configuration on a device or emulator (API 34+), or
install a built APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project structure

```
Proyecto/
├─ app/
│  ├─ build.gradle.kts            # module config, dependencies
│  ├─ proguard-rules.pro
│  └─ src/
│     ├─ main/java/fm/rizx/player/
│     │  ├─ core/                 # error, network, cache, DI modules, formatting
│     │  ├─ domain/               # models, provider/repository contracts, use cases (NO Android)
│     │  │  ├─ model/  provider/  repository/  usecase/  playback/
│     │  ├─ data/                 # providers, remote clients, local stores, mappers, repositories
│     │  │  ├─ provider/  remote/  repository/  local/  download/  plugin/  search/  canvas/
│     │  ├─ playback/service/     # PlaybackService (MediaSessionService) + stream resolver
│     │  └─ ui/                   # Compose screens, theme, navigation, components
│     │     ├─ screens/  components/  theme/  navigation/  player/  home/  library/ …
│     └─ test/java/fm/rizx/player/ # JVM unit tests
├─ build.gradle.kts · settings.gradle.kts
├─ gradle/ · gradlew · gradlew.bat
└─ gradle.properties
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the layering rules and why the dependency direction matters.

## Troubleshooting

- **SDK not found** — check `local.properties` `sdk.dir`.
- **JitPack dependency (NewPipeExtractor) fails to resolve** — the JitPack repository is scoped in
  `settings.gradle.kts` to `com.github.[Tt]eam[Nn]ew[Pp]ipe*`; a network hiccup on first resolve usually
  fixes itself on retry.
- **Wrong JDK** — ensure Gradle uses JDK 17+ (Android Studio: *Settings → Build Tools → Gradle → Gradle
  JDK*).
