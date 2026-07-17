# Features

A tour of what Rizx Player does. Everything below is built on the shared domain pipeline described in
[ARCHITECTURE.md](ARCHITECTURE.md), so features compose cleanly — a local file, a downloaded track, and a
Deezer track are all just `Track`s with different `ProviderRef` sources.

## Playback

- **Background playback** via a `MediaSessionService`-owned ExoPlayer, with a **system media notification**
  and lock-screen transport controls.
- **Now Playing** — artwork, scrubbable progress, shuffle & repeat, and an **Up-Next drawer** you can pull
  up to see the queue and tap any song to jump to it.
- **Mini-player** — a floating bar across the app that expands into Now Playing.
- **Gapless & crossfade** — volume-envelope fades between tracks.
- **Loudness normalization** — evens out volume across sources (`LoudnessEnhancer`).
- **Adaptive quality** — picks stream quality based on current network conditions.
- **Resume after process death** — reopen the app and it returns to the last track at the exact second
  (identities only are persisted, never ephemeral stream URLs).
- **Equalizer** — audio effects screen.
- **Lyrics** — fetched per track and shown on their own screen.
- **Canvas video preview** — an optional muted music-video loop behind Now Playing.

## Queue & radio

- **Contextual Next/Prev** — navigation traverses whatever you started from: an album, an artist, a
  playlist, your Liked songs, Recently Played, Downloads, or the Local library.
- **Endless radio** — seed a radio from an artist/track and the queue auto-refills with similar tracks.
- **Shuffle** with faithful un-shuffle (restores the original order, even with duplicate entries).
- **Repeat** — off / one / all.

## Search

Tabbed search across sources:

- **Songs** — track search.
- **Artists** / **Albums** — dedicated entity search.
- **Playlists** — Deezer + YouTube playlists (Spotify playlists remain importable by URL).
- **Underground** — exclusive/independent material surfaced from YouTube (remixes, edits) and SoundCloud,
  grouped by source.

## Home

Tabbed feed: **Songs**, **For you** (editorial playlists), **Albums**, **Artists** — populated from real
provider charts/editorial data.

## Library

Tabbed library: **All**, **Playlists**, **Liked**, **Recent**, and **Local**.

- **Liked / favorites** — heart any track from anywhere; it round-trips through persistence and plays back
  from Liked.
- **User playlists** — create, edit, and reorder your own playlists.
- **Recently played** — recorded provider-agnostically as tracks play.
- **Local** — see below.

## Local music player

- **Scans the device library** (`MediaStore.Audio`) and lists your own `mp3` / `m4a` / `flac` / … files.
- Browse by **Songs / Albums / Artists**, with album and artist detail screens.
- Plays through the **same** Media3 pipeline as remote content (via `content://` URIs), so favorites,
  playlists, queue, and Recently Played all work for local files too.
- Requires only the runtime **`READ_MEDIA_AUDIO`** permission, requested contextually when you open the
  Local library; denial is non-fatal (you get an empty state with a grant button).

## Downloads & offline

- **Download** tracks for offline listening; a foreground service keeps the batch alive.
- Downloaded files are resolved from local storage **before** any network attempt.
- Bytes are stored as delivered by the source (e.g. YouTube audio as M4A); nothing is transcoded.

## Playlist import

- **By URL** — Spotify, YouTube / YouTube Music, and Deezer playlist links.
- **By file** — Nuclear-JSON exports and Exportify CSV files.
- Imports are persisted and become normal, editable playlists.

All import paths are **keyless** — e.g. Spotify playlists are read via the public embed data, not any
private API secret.

## Plugins

- A **sandboxed QuickJS runtime** can download and run real Nuclear plugins.
- The sandbox exposes only `fetch` (no DOM, no filesystem, no Android APIs), with per-call timeouts and
  per-plugin crash isolation — a misbehaving plugin can't take down the app.
- A native **Plugins** screen shows each plugin's version, health, and an enable/disable toggle.
- Plugins that genuinely can't run on Android (desktop-only integrations) are hidden rather than shown
  broken.

## Settings

- Every option is functional and persisted (DataStore-backed).
- Theme switching (see below), playback resolver preferences, normalization, crossfade/gapless, adaptive
  quality, and more.

## Design & theming

- **Material 3** with a custom brutalist / Nothing-OS-inspired visual language (dot-matrix numerals,
  monospace labels, tactile press feedback and haptics).
- Two switchable themes — a warm **Paper (light)** and a near-black **Ivory (dark)** — driven entirely by
  design tokens (`RizxTheme.colors`), with no hard-coded colors in screens.
- Real cover art everywhere via Coil, with tinted procedural fallbacks when art is missing.

## Content sources

All streaming and metadata come from **keyless** sources: Deezer, Audius, iTunes, YouTube, and SoundCloud.
No API keys or secrets ship in the app. See [PROVIDERS.md](PROVIDERS.md) for exactly what each source
provides and how.
