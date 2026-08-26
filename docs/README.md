# Documentation map and current status

_Reviewed against the repository on 2026-08-25 · Rizx Player 1.0.0 (version code 3)_

This is the entry point for Rizx documentation. The Android Studio project is in `Proyecto/`; product
documentation is in this directory. Specifications and ADRs are retained as the history of individual
implementation slices, so their original scope and “deferred” sections are not a list of current product
gaps. When a historical document conflicts with the shipped code, the current code and the active
documents below are authoritative.

## Current project snapshot

| Area | Current state |
|---|---|
| Android | `minSdk 26`, `targetSdk 36`, `compileSdk 36` |
| App | `fm.rizx.player`, version `1.0.0` (`versionCode 3`) |
| Build | Gradle 8.12 · AGP 8.9.1 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Hilt 2.52 |
| Java | Java 21 recommended to run Gradle; Java 17 bytecode target; Gradle JVM must be 17–23 |
| Shape | One Android application module plus the `:baselineprofile` test module |
| Persistence | Room schema v7 (exported schemas 4–7) · DataStore · filesystem playback snapshot · sync outbox |
| Automated verification | 1,651 JVM tests in 186 suites, all passing; `lintReleaseTest` and `assembleReleaseTest` pass |
| Source size | 476 main Kotlin files · 195 JVM test files · 4 instrumented-test files |

The automated results above are a verified repository snapshot, not a promise that every provider is
online or that device-only flows have passed on every Android release. See the manual checklist before a
public build.

## Active documentation

| Document | Purpose |
|---|---|
| [`../README.md`](../README.md) | Product overview, architecture summary and quick build commands |
| [`BUILD.md`](BUILD.md) | Reproducible build, Java/Gradle setup, signing, tests and troubleshooting |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Current layers, identity, resolution, playback, persistence and DSP |
| [`FEATURES.md`](FEATURES.md) | Current user-facing features and device compatibility |
| [`PROVIDERS.md`](PROVIDERS.md) | Native providers, capability model and plugin runtime |
| [`plugins/PLUGIN_GUIDE.md`](plugins/PLUGIN_GUIDE.md) | Author-facing plugin contract and security limits |
| [`plugins/PLUGIN_SPEC_FOR_AGENTS.md`](plugins/PLUGIN_SPEC_FOR_AGENTS.md) | Exact plugin shapes and implementation rules |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Data processing, network access and permissions |
| [`LICENSING.md`](LICENSING.md) | GPL obligations, trademarks and implementation notes |
| [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) | Direct dependencies, algorithms, fonts and licenses |
| [`../share-site/README.md`](../share-site/README.md) | What a share-link domain must serve (App Links statement, landing page) |

## Specifications and decisions

The app was built slice by slice under Spec Driven Development: each feature has a specification and,
where a trade-off was made, an architecture decision record. Those working documents, the release
checklist and the author's planning notes are maintained outside this repository and are not part of
the published documentation. Where a design decision matters to a reader of the code, the active
documents above state it directly (identity model, ephemeral streams, keyless-only sources, plugin
isolation, local-first sync). The Corresponding Source of the app is complete without them.

## Known engineering limits

- The queue repository is in-memory while a sanitized playback/session snapshot restores the current
  queue and position after process death.
- Crossfade is a volume-envelope fade between items, not two overlapping main players. Canvas owns a
  separate muted video-only ExoPlayer; the playback service remains the sole owner of the audio player.
- “Normalize volume” currently applies a fixed `LoudnessEnhancer` gain, not measured LUFS normalization.
- Room exports begin at schema 4; the instrumented migration suite proves 4 → 5, 5 → 6 and 6 → 7, not
  versions 1–3.
- `quickjs-kt` cannot interrupt an infinite JavaScript loop. Plugin restart recovers the host, but the
  wedged runtime thread remains until the Android process exits. Plugins also retain outbound network
  access through the guarded `fetch` bridge.
- `jaudiotagger` remains an Android-compatibility risk that must be re-evaluated before public release.
- The shared HTTP `User-Agent` is a constant (`RizxPlayer/1.0` plus the repository URL) rather than
  being derived from `BuildConfig.VERSION_NAME`; bump it with the major version.
- Account sync is local-first and optional. The realtime invalidation channel is open only while the
  app is on screen; in the background the debounced, foreground and 6-hour schedules are the guarantee.
  The backend (a Supabase project with the schema, the `rizx_sync` RPC and a few Edge Functions) is a
  separate deployment that a fork has to provide; without its configuration the app hides those
  features and everything else works.
- Provider endpoints are third-party and can change independently; each failure is isolated by design.

## Documentation maintenance

For a release, update this snapshot, `BUILD.md`, the version/date in the privacy and license reports,
and the in-app license data (`ui/screens/LicenseData.kt` **and** `THIRD_PARTY_LICENSES.md` — the
in-app list is what a user of the APK sees, so a new dependency must land in both). Documentation must
stay publishable as-is: no credentials, no configuration values, no personal data, no links to files
that are not in the repository.
