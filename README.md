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
  SoundCloud (via NewPipeExtractor), Apple/iTunes (search, editorial, charts), plus four keyless lyrics
  services and Wikipedia artist bios. No secrets ship in the app.
- 📱 **Local music player** — a MediaStore scan (Songs / Albums / Artists, A–Z rail, sort, codec badges)
  plus a Storage-Access-Framework file explorer that plays audio from any folder with no permission, and
  local-only playlists — all through the same pipeline as streaming.
- ⬇️ **Downloads that look right anywhere** — four formats (**Original M4A · Opus · MP3 320 · FLAC**),
  each with embedded cover/artist/album tags; segmented multi-connection fetching; optional publishing
  into the shared `Music/Rizx` folder so every other app can see the files.
- ▶️ **Real background playback** — a single `MediaSessionService`-owned ExoPlayer with a system media
  notification, lock-screen controls, gapless/crossfade, an optional fixed loudness boost, adaptive quality, an
  optional **Hi-Res mode** (Opus 160k, 32-bit float output, DAC readout), and
  **resume-after-process-death** (returns to the exact second).
- 🎛️ **Automatic equalizer** — a per-song EQ curve: genre baseline refined by the track's own measured
  spectrum, applied mean-zero. The manual EQ stays available.
- 🎧 **Smart 8D audio** — adaptive stereo spatialization for headphones: panning, a sub-millisecond
  interaural delay, head shadow, crossfeed and a little ambience, tuned per genre and then refined from
  the recording's own width, weight and tempo. Off by default, toggles live mid-song, and stands down by
  itself over the phone's speaker. It is *not* Atmos, multichannel, HRTF or a quality improvement.
- 🎤 **Karaoke lyrics** — timed lyrics up to word-by-word / letter-by-letter precision (LRCLIB, NetEase,
  KuGou, Musixmatch), with a live spectrum waveform seek bar and animated **covers** — motion album
  artwork from Apple Music and TIDAL, with a muted music-video fallback.
- 🧠 **On-device recommendations** — a local listening log (plays/skips/completions) drives daily mixes,
  "Similar to …" rows, and endless radio (YouTube Music mixes or Deezer artist radio). Nothing about
  your listening leaves the phone.
- 🔎 **Rich search & genre browsing** — Songs, Artists, Albums, Playlists, an *Underground* tab of
  YouTube/SoundCloud exclusives, on-device search history and suggestion pills, and a 28-tile browse
  wall where each tile opens a **genre hub** (that genre's songs, playlists, artists and albums) —
  addressed by genre id, never by searching the genre's name.
- 🎙️ **Music recognition (Audio ID)** — identify what is playing in the room. The microphone is opened
  only for the few seconds you ask for; the audio becomes an acoustic fingerprint **on the phone** and is
  then discarded — no recording is stored or transmitted. A match is located in Rizx's own catalogue by
  ISRC or Apple id first, and by a *scored* search after that, so it plays the recording you heard
  rather than the first same-titled karaoke version.
- 📥 **Playlist import** — by URL (Spotify, YouTube / YT Music, Deezer, Apple Music — fully paginated where the
  source allows) or from Nuclear-JSON / Exportify-CSV files; imports become normal editable playlists.
- ❤️ **Library** — favorites, user playlists, recently played, per-tab filter bars, and contextual
  queue/radio (Next/Prev traverse the album/artist/playlist you started from).
- 🔌 **Plugin runtime** — a sandboxed QuickJS runtime runs compatible Nuclear plugins (`fetch` plus a
  pure-JS `DOMParser`, no filesystem or Android APIs), with per-plugin isolation and quarantine. The
  store hides integrations replaced by native code or unsupported by this host; the community lossless
  FLAC source is itself a plugin, and the repository bundles no plugin archives.
- 🌐 **Localized & themed** — English/Español/Português/Français, System/Light/Dark theme modes, data
  saver, and a brutalist Nothing-OS-inspired design language with semantic haptics.

See **[docs/FEATURES.md](docs/FEATURES.md)** for the full feature tour.

---

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose · Material 3 · Navigation Compose |
| Playback | Media3 / ExoPlayer 1.5.1 · `MediaSessionService` |
| DI | Hilt (KSP) |
| Persistence | Room (exported schemas) · DataStore · kotlinx.serialization |
| Networking | Retrofit · OkHttp |
| Images | Coil |
| Extraction | NewPipeExtractor (keyless YouTube/SoundCloud) |
| Plugin engine | QuickJS via `quickjs-kt` (sandboxed) |
| Audio formats | jaudiotagger (tags) · jump3r (MP3 encode) · in-repo Ogg Opus tagger |
| Tests | JUnit4 · MockK · Turbine · MockWebServer |
| Build | Gradle 8.12 (Kotlin DSL) · AGP 8.9.1 · KSP 2.0.21-1.0.28 · `minSdk 26` · `compileSdk 36` · JDK 21 recommended |

421 main Kotlin files, 172 JVM-test files and 3 instrumented-test files across a clean `domain` / `data`
/ `playback` / `ui` layering. The current JVM suite contains 1,473 passing tests. Full dependency list &
licenses: **[docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md)**.

---

## Architecture at a glance

```
UI (Compose) → ViewModel → UseCase → Repository / Controller → Provider · Room · DataStore · Media3
```

- **`domain/`** — pure Kotlin: models, provider/repository contracts, use cases. **No Android imports.**
- **`data/`** — providers (Deezer, Audius, Apple, YouTube, SoundCloud, lyrics…), Room/DataStore stores,
  DTO↔domain mappers, repositories, the download/transcode pipeline, canvas, and the plugin runtime.
  Depends on `domain`.
- **`playback/`** — `PlaybackService : MediaSessionService` owns the *single* ExoPlayer; the stream
  resolver, audio effects (AutoEQ, fixed loudness boost, float output) and the PCM tap live here. Depends on
  `domain` + Media3.
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
│  │  ├─ schemas/            # exported Room schema JSONs (committed)
│  │  └─ src/main/java/fm/rizx/player/
│  │     ├─ core/            # error, network, cache, DI, formatting
│  │     ├─ domain/          # models, provider/repository contracts, use cases (no Android)
│  │     ├─ data/            # providers, local stores, remote clients, mappers, repositories
│  │     ├─ playback/        # PlaybackService, stream resolver, audio effects
│  │     └─ ui/              # Compose screens, theme, navigation
│  ├─ baselineprofile/       # startup-profile generator (com.android.test module)
│  ├─ build.gradle.kts · settings.gradle.kts · gradlew
├─ docs/                     # project documentation (this repo)
├─ LICENSE · NOTICE          # AGPL-3.0 + upstream attribution
└─ README.md
```

Note: `Proyecto/app/src/main/assets/plugins/` is **deliberately git-ignored** — the repo distributes no
plugin archives, so a fresh clone builds a generic plugin host with zero bundled plugins (everything
else works). See [docs/BUILD.md](docs/BUILD.md#project-structure).

---

## Build & run

Use JDK 21 to run Gradle (17–23 are compatible; **Java 25 is not**) and install the Android SDK with
`compileSdk 36`. The app **runs on Android 8.0+ (API 26)** —
newer-API features degrade gracefully behind version checks
([details](docs/FEATURES.md#device-compatibility)).

```bash
cd Proyecto
./gradlew assembleDebug         # build the debug APK
./gradlew testDebugUnitTest     # run unit tests
./gradlew assembleReleaseTest   # minified smoke build (debug-signed)
./gradlew assembleRelease       # distributable build — requires a real keystore, fails without one
```

Or open `Proyecto/` in Android Studio, select JDK 21 for Gradle, and run the **app** configuration on a device
or emulator (API 26+). Detailed instructions, signing (including creating a keystore), and
troubleshooting: **[docs/BUILD.md](docs/BUILD.md)**.

---

## Documentation

| Doc | What's in it |
|---|---|
| [docs/README.md](docs/README.md) | Documentation index, verified project snapshot and known limits |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, dependency rules, identity model, streaming, playback, queue, downloads |
| [docs/FEATURES.md](docs/FEATURES.md) | Full feature tour + device-compatibility table |
| [docs/PROVIDERS.md](docs/PROVIDERS.md) | The provider model + every content source |
| [docs/plugins/PLUGIN_GUIDE.md](docs/plugins/PLUGIN_GUIDE.md) | Writing a plugin: the contract, the rules, and what the sandbox does and does not guarantee |
| [docs/plugins/PLUGIN_SPEC_FOR_AGENTS.md](docs/plugins/PLUGIN_SPEC_FOR_AGENTS.md) | The same contract as imperative rules, exact shapes and a template, for coding agents |
| [docs/BUILD.md](docs/BUILD.md) | Build, run, test, signing, Room schemas, project structure |
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
