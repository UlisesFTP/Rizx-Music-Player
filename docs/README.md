# Documentation map and current status

_Reviewed against the repository on 2026-08-18 · Rizx Player 0.2.0 (version code 2)_

This is the entry point for Rizx documentation. The Android Studio project is in `Proyecto/`; product
documentation is in this directory. Specifications and ADRs are retained as the history of individual
implementation slices, so their original scope and “deferred” sections are not a list of current product
gaps. When a historical document conflicts with the shipped code, the current code and the active
documents below are authoritative.

## Current project snapshot

| Area | Current state |
|---|---|
| Android | `minSdk 26`, `targetSdk 36`, `compileSdk 36` |
| App | `fm.rizx.player`, version `0.2.0` (`versionCode 2`) |
| Build | Gradle 8.12 · AGP 8.9.1 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Hilt 2.52 |
| Java | Java 21 recommended to run Gradle; Java 17 bytecode target; Gradle JVM must be 17–23 |
| Shape | One Android application module plus the `:baselineprofile` test module |
| Persistence | Room schema v6 (exported schemas 4–6) · DataStore · filesystem playback snapshot · sync outbox |
| Automated verification | 1,489 JVM tests in 168 suites, all passing; `lintReleaseTest` and `assembleReleaseTest` pass |
| Source size | 445 main Kotlin files · 175 JVM test files · 4 instrumented-test files |

The automated results above are a verified repository snapshot, not a promise that every provider is
online or that device-only flows have passed on every Android release. See the manual checklist before a
public build.

## Active documentation

| Document | Purpose |
|---|---|
| [`AGENT_CONTEXT.md`](AGENT_CONTEXT.md) | Compact current context shared by Claude Code and OpenCode |
| [`../README.md`](../README.md) | Product overview, architecture summary and quick build commands |
| [`BUILD.md`](BUILD.md) | Reproducible build, Java/Gradle setup, signing, tests and troubleshooting |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Current layers, identity, resolution, playback, persistence and DSP |
| [`FEATURES.md`](FEATURES.md) | Current user-facing features and device compatibility |
| [`PROVIDERS.md`](PROVIDERS.md) | Native providers, capability model and plugin runtime |
| [`plugins/PLUGIN_GUIDE.md`](plugins/PLUGIN_GUIDE.md) | Author-facing plugin contract and security limits |
| [`plugins/PLUGIN_SPEC_FOR_AGENTS.md`](plugins/PLUGIN_SPEC_FOR_AGENTS.md) | Exact plugin shapes and implementation rules |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Data processing, network access and permissions |
| [`LICENSING.md`](LICENSING.md) | AGPL obligations, upstream attribution and modifications |
| [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) | Direct dependencies, algorithms, fonts and licenses |
| [`checklists/manual-test-checklist.md`](checklists/manual-test-checklist.md) | Release-device verification checklist |

## Specifications and decisions

- `specs/001`–`020` record the vertical slices that established the app. All are implemented; spec 021
  is the active optional-account, synchronization and portable-sharing slice. Older
  “deferred” sections describe what was outside that slice, not necessarily what is absent today.
- `adr/0008`–`0028` are decision records. They intentionally retain context that later ADRs superseded.
  In particular, ADR 0014/0019/0026 supersede the early native-only plugin position.
- `../nuclear_android_docs_specs_package/` is the original planning package and is archival.
- `../PROJECT_CONTEXT_NUCLEAR_ANDROID.md`, `../RAG_KNOWLEDGE_BASE_NUCLEAR_TO_ANDROID.md`,
  `../SDD_CODEX_META_PLAN_NUCLEAR_ANDROID.md`, `../ROADMAP.md`, `../NUCLEAR_UPSTREAM_STUDY.md` and
  `../SPEC_REVIEW_VS_UPSTREAM.md` are design/history references. They do not describe the live feature
  surface as reliably as the active documents above.

## Known engineering limits

- The queue repository is in-memory while a sanitized playback/session snapshot restores the current
  queue and position after process death.
- Crossfade is a volume-envelope fade between items, not two overlapping main players. Canvas owns a
  separate muted video-only ExoPlayer; the playback service remains the sole owner of the audio player.
- “Normalize volume” currently applies a fixed `LoudnessEnhancer` gain, not measured LUFS normalization.
- Room exports begin at schema 4; the instrumented migration suite proves 4 → 5 and 5 → 6, not
  versions 1–3.
- `quickjs-kt` cannot interrupt an infinite JavaScript loop. Plugin restart recovers the host, but the
  wedged runtime thread remains until the Android process exits. Plugins also retain outbound network
  access through the guarded `fetch` bridge.
- `jaudiotagger` remains an Android-compatibility risk that must be re-evaluated before public release.
- The shared HTTP `User-Agent` still reports `RizxPlayer/0.1` while the app is 0.2.0; this should be
  derived from `BuildConfig.VERSION_NAME` before release.
- Provider endpoints are third-party and can change independently; each failure is isolated by design.

## Documentation maintenance

For a release, update this snapshot, `BUILD.md`, the version/date in the privacy and license reports,
and the in-app license data from the resolved dependency graph. Never rewrite an accepted ADR to make a
later decision appear inevitable; add a new ADR or an explicit supersession note instead.
