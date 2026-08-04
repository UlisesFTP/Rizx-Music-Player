# Rizx Player — Android app (`:app`)

This is the Android Studio project for **Rizx Player** (package `fm.rizx.player`). Open **this** directory
in Android Studio, or build from here with the Gradle wrapper.

```bash
./gradlew assembleDebug         # build the debug APK
./gradlew testDebugUnitTest     # run unit tests (JVM, no device)
```

- **Requirements:** JDK 17+, Android SDK with `compileSdk 36`. The app runs on API 26+ (`minSdk 26`).
- **Modules:** the `:app` module plus `:baselineprofile` (startup-profile generator), Kotlin DSL build.
- **Local config:** create `local.properties` with `sdk.dir=…` (git-ignored). Release signing reads from an
  uncommitted `keystore.properties`; without it `assembleRelease` **fails on purpose** — build
  `assembleReleaseTest` for a minified, debug-signed smoke APK. Details: [`../docs/BUILD.md`](../docs/BUILD.md).

## Documentation

Project-level documentation lives at the repository root:

- [`../README.md`](../README.md) — project overview
- [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) — layers, identity model, streaming, playback, queue
- [`../docs/FEATURES.md`](../docs/FEATURES.md) — full feature tour
- [`../docs/PROVIDERS.md`](../docs/PROVIDERS.md) — the provider model & content sources
- [`../docs/BUILD.md`](../docs/BUILD.md) — detailed build/run/test guide & project structure
- [`../docs/LICENSING.md`](../docs/LICENSING.md) — AGPL compliance & attribution

## License

AGPL-3.0 — see [`../LICENSE`](../LICENSE) and [`../NOTICE`](../NOTICE). Rizx is a derived work of
[nukeop/nuclear](https://github.com/nukeop/nuclear). Bundled fonts are SIL OFL 1.1.
