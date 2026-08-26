# Licensing & attribution

_Reviewed against version 1.0.0 on 2026-08-25. This is engineering documentation, not legal advice._

## App license: GPL-3.0

Rizx Player is licensed under the **GNU General Public License, version 3.0 (GPL-3.0)**. The full text is
in [`LICENSE`](../LICENSE); the notices are in [`NOTICE`](../NOTICE).

The only copyleft dependency is **NewPipeExtractor (GPL-3.0)**, which requires a GPLv3-compatible
licence for the combined work. GPL-3.0 *is* that licence — the same one — so no cross-licence
combination clause is involved. A permissive licence (MIT/Apache/BSD) is **not** available while that
dependency ships.

## Trademarks and branding

The GPL-3.0 grant covers the **source code**. It grants **no** right to the name "Rizx" / "Rizx
Player", the Rizx logomark, the app icon or the visual branding, which are reserved by the copyright
holder. A fork may redistribute the software under the GPL, but must ship under its **own** name and
icon and must not state or imply that it is Rizx, or endorsed by or affiliated with it.

This mirrors what Apache-2.0 §6 says explicitly and what the GPL family leaves to trademark law: a
copyleft licence is not permission to use somebody's brand.

## Corresponding Source (GPL §6)

The complete Corresponding Source for each distributed build is this public Git repository, tagged to match
the build's `versionName` / `versionCode`. The in-app **About** screen links back to it. Build
configuration needed to reproduce a build (Gradle) is included; **signing keys and secrets are not**.

The optional account/sync backend is a separate program that the app talks to over HTTPS. It is not
part of the app's Corresponding Source (GPL §1 — it is neither linked nor required to run the program),
and a fork can point the app at its own deployment or ship without one.

## Appropriate Legal Notices (GPL §5(d))

The app has an interactive interface, so it displays the notices the GPL asks for: the **About** screen
shows the copyright line, the statement that the program comes with **no warranty**, that it is free
software redistributable under GPL-3.0, and where to read the licence; **About → Open-source licenses**
lists every bundled component with its licence and links the licence texts. These notices must be
preserved in modified versions (§5(d)); a fork may add its own but may not remove them.

## Notable implementation notes

- Native Android throughout: Kotlin · Jetpack Compose · Material 3 · Navigation Compose.
- Media3 / ExoPlayer + `MediaSessionService` background playback.
- Room + DataStore persistence, with a sanitized playback snapshot that never stores resolved stream URLs.
- Provider architecture: separate metadata and streaming providers behind a registry, with two-phase
  ephemeral stream resolution and independently failable providers.
- Native, keyless full-length YouTube audio via NewPipeExtractor, and a sandboxed QuickJS runtime that
  executes third-party plugins written for the Nuclear plugin API (downloaded at runtime, never bundled).
- A download **format pipeline**: Original/Opus/MP3-320/FLAC, on-device MP3
  encoding, a WebM→Ogg Opus remux with a from-scratch Ogg comment/picture tagger, embedded tags in every
  format, and optional MediaStore publishing to the shared `Music/` folder.
- Word/letter-level **karaoke lyrics** over a ranked multi-provider chain (LRCLIB, NetEase, KuGou,
  Musixmatch, lyrics.ovh).
- An animated-cover **canvas** resolved from Apple motion artwork, TIDAL video covers or the song's own
  music video, behind policy gating.
- An **on-device recommendations engine** (listening log, taste clusters, daily mixes) and a rebuilt
  streaming-style Home feed; an **automatic per-song equalizer** (genre baseline + measured spectrum).
- Adaptive **Smart 8D** stereo spatialization and standalone 8D MP3 rendering, implemented in the native
  playback/download pipeline without a new DSP dependency.
- Full app **localization** (en/es/pt/fr) and an Android 8.0+ compatibility layer (version-gated APIs).
- An optional, local-first **account and sync** client (one RPC transaction per sync, a realtime
  invalidation channel while on screen, per-device listening counters summed on read) and portable
  playlist export/links; and three **home screen widgets** driven through the media session.
- Ambient **music recognition** ("Audio ID"): on-device acoustic fingerprinting of
  microphone audio (a Kotlin reimplementation of the format documented by
  [SongRec](https://github.com/marin-m/SongRec), GPL-3.0 — no SongRec code bundled, no Shazam SDK), a
  keyless lookup, and a resolver that locates the identified recording in this app's own catalogue by
  ISRC or Apple id before any text search. Captured audio is processed in memory and discarded.

## Third-party components

Bundled open-source libraries and their licenses are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) and surfaced in-app via **About → Open-source
licenses**. Highlights:

- Most AndroidX / Compose / Media3 / Hilt / Room / Retrofit / OkHttp / Coil dependencies are **Apache-2.0**.
- **NewPipeExtractor** is **GPL-3.0** — the same licence as this app, so the combined work is simply
  GPL-3.0. Downloaded plugins are separate *data* (their own licenses), transpiled and run at runtime,
  not linked into the APK.
- **jaudiotagger** (embedded tag writing) is **LGPL** and **jump3r** (the pure-Java LAME MP3 encoder) is
  **LGPL-2.1+** — both used as unmodified library jars, compatible with distributing the combined work
  under GPL-3.0.
- Bundled fonts (Space Grotesk, Manrope, Martian Mono, Doto) are licensed under the **SIL Open Font License
  1.1**.

## Content

Search results, streams, lyrics, covers and artist pages are fetched at runtime from third-party
services (Deezer, Audius, Apple's iTunes Search API and editorial RSS, YouTube, SoundCloud, LRCLIB,
NetEase, KuGou, Musixmatch, lyrics.ovh, Wikipedia) under **their** terms. **No** third-party API keys,
code, or assets are bundled in the app. See [PROVIDERS.md](PROVIDERS.md).

## Privacy

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
