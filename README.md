# Rizx Player

**A native Android music player.** Kotlin · Jetpack Compose · Material 3 · Media3/ExoPlayer.

Rizx is a from-scratch Android reimplementation of the *business logic* of
[**Nuclear**](https://github.com/nukeop/nuclear) — the open-source desktop music player — rebuilt on
native Android architecture. It streams full-length tracks from free, **keyless** sources, plays your
**on-device** music, works **offline**, and runs background playback through a real
`MediaSessionService`. It is **not** a port of Nuclear's React/Tauri desktop UI: only the domain logic
(providers, queue, two-phase stream resolution, playback, favorites, playlists) is shared in spirit.

> **License:** GNU Affero General Public License v3.0 (AGPL-3.0). Rizx is a derived work of Nuclear and
> carries the full AGPL obligations — see [Licensing](#licensing).

---

## Highlights

- 🎧 **Full-length streaming, no API keys** — Deezer (metadata/charts), Audius (full tracks), YouTube &
  SoundCloud (via NewPipeExtractor), iTunes (search + 30 s previews). Every provider is keyless; no secrets
  ship in the app.
- 📱 **Local music player** — scans the device library (`MediaStore`) and plays your own `mp3` / `m4a` /
  `flac` files through the same pipeline, browsable by Songs / Albums / Artists.
- ⬇️ **Downloads & offline playback** — save tracks for offline listening; resolved from local storage
  before ever touching the network.
- ▶️ **Real background playback** — a single `MediaSessionService`-owned ExoPlayer with a system media
  notification, lock-screen controls, gapless/crossfade, loudness normalization, and
  **resume-after-process-death** (returns to the exact second).
- 🔎 **Rich search** — tabs for Songs, Artists, Albums, Playlists, and an *Underground* tab surfacing
  exclusive YouTube/SoundCloud material.
- 📥 **Playlist import** — import playlists by URL (Spotify, YouTube / YT Music, Deezer) or from a
  Nuclear-JSON / Exportify-CSV file.
- ❤️ **Library** — favorites, user playlists, recently played, and contextual queue/radio (Next/Prev
  traverse the album/artist/playlist you started from, or an endless artist radio).
- 🔌 **Plugin runtime** — a sandboxed QuickJS runtime that can run real Nuclear plugins (fetch-only, no
  DOM/FS/Android access), with per-plugin crash isolation.
- 🎚️ **Player extras** — synced-ish lyrics, an equalizer, a muted music-video "canvas" preview, shuffle &
  repeat, and an Up-Next queue drawer.

See **[docs/FEATURES.md](docs/FEATURES.md)** for the full feature tour.

---

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose · Material 3 · Navigation Compose |
| Playback | Media3 / ExoPlayer 1.5.1 · `MediaSessionService` |
| DI | Hilt (KSP) |
| Persistence | Room · DataStore · kotlinx.serialization |
| Networking | Retrofit · OkHttp |
| Images | Coil |
| Extraction | NewPipeExtractor (keyless YouTube/SoundCloud) |
| Plugin engine | QuickJS via `quickjs-kt` (sandboxed) |
| Tests | JUnit4 · MockK · Turbine · MockWebServer |
| Build | Gradle (Kotlin DSL) · `minSdk 34` · `compileSdk 36` · JDK 17 |

~200 Kotlin source files and ~60 unit-test files across a clean `domain` / `data` / `playback` / `ui`
layering. Full dependency list & licenses: **[docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md)**.

---

## Architecture at a glance

```
UI (Compose) → ViewModel → UseCase → Repository / Controller → Provider · Room · DataStore · Media3
```

- **`domain/`** — pure Kotlin: models, provider/repository contracts, use cases. **No Android imports.**
- **`data/`** — providers (Deezer, Audius, iTunes, YouTube, SoundCloud…), Room/DataStore stores, DTO↔domain
  mappers, repositories. Depends on `domain`.
- **`playback/`** — `PlaybackService : MediaSessionService` owns the *single* ExoPlayer; the stream resolver
  and mappers live here. Depends on `domain` + Media3.
- **`ui/`** — Compose screens, theme tokens, navigation. Talks only to ViewModels/use cases — never to a
  provider or ExoPlayer directly.

Two rules do most of the load-bearing work:

- **`ProviderRef(provider, id)` is the canonical identity** for all upstream content — never a title, URL,
  or artist name. It round-trips through persistence, so favorites/playlists/queue/recents work uniformly
  across every source, including local files.
- **Stream resolution is two-phase** (`Track → StreamCandidate → just-in-time Stream URL`) and resolved
  URLs are **ephemeral** — never persisted.

Full write-up: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** · providers: **[docs/PROVIDERS.md](docs/PROVIDERS.md)**.

---

## Repository layout

The Android Studio project lives in **`Proyecto/`** (package `fm.rizx.player`):

```
Rizx-Music-Player/
├─ Proyecto/                 # the Android app (open THIS in Android Studio)
│  ├─ app/                   # single :app module
│  │  └─ src/main/java/fm/rizx/player/
│  │     ├─ core/            # error, network, cache, DI, formatting
│  │     ├─ domain/          # models, provider/repository contracts, use cases (no Android)
│  │     ├─ data/            # providers, local stores, remote clients, mappers, repositories
│  │     ├─ playback/        # PlaybackService + stream resolver
│  │     └─ ui/              # Compose screens, theme, navigation
│  ├─ build.gradle.kts · settings.gradle.kts · gradlew
├─ docs/                     # project documentation (this repo)
├─ LICENSE · NOTICE          # AGPL-3.0 + upstream attribution
└─ README.md
```

---

## Build & run

Requires JDK 17+ and the Android SDK (API 34–36).

```bash
cd Proyecto
./gradlew assembleDebug         # build the debug APK
./gradlew testDebugUnitTest     # run unit tests
```

Or open `Proyecto/` in Android Studio (Meerkat / AGP 8.9+) and run the **app** configuration on a device or
emulator (API 34+). Detailed instructions, signing, and troubleshooting: **[docs/BUILD.md](docs/BUILD.md)**.

---

## Documentation

| Doc | What's in it |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, dependency rules, identity model, streaming, playback, queue |
| [docs/FEATURES.md](docs/FEATURES.md) | Full feature tour |
| [docs/PROVIDERS.md](docs/PROVIDERS.md) | The provider model + every content source |
| [docs/BUILD.md](docs/BUILD.md) | Build, run, test, and project structure |
| [docs/LICENSING.md](docs/LICENSING.md) | AGPL compliance, attribution, corresponding source |
| [docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md) | Bundled dependencies & their licenses |
| [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md) | Privacy policy |

---

## Licensing

Rizx Player is licensed under the **GNU Affero General Public License v3.0** ([`LICENSE`](LICENSE)).

It is an independent, clean-room reimplementation of the *business logic* of
[**nukeop/nuclear**](https://github.com/nukeop/nuclear) (also AGPL-3.0). As a derived work it carries the
full AGPL obligations independently — the license text, upstream attribution ([`NOTICE`](NOTICE)), the
Corresponding Source (this repository), and a log of major modifications are preserved with every build.
Rizx is not affiliated with or endorsed by the Nuclear project.

Content is fetched at runtime from third-party services under their own terms; no third-party API keys,
code, or assets are bundled. Bundled fonts are licensed under the SIL Open Font License 1.1. See
**[docs/LICENSING.md](docs/LICENSING.md)** for the full picture.
