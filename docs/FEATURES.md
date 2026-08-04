# Features

A tour of what Rizx Player does. Everything below is built on the shared domain pipeline described in
[ARCHITECTURE.md](ARCHITECTURE.md), so features compose cleanly — a local file, a downloaded track, and a
Deezer track are all just `Track`s with different `ProviderRef` sources.

## Playback

- **Background playback** via a `MediaSessionService`-owned ExoPlayer, with a **system media notification**
  and lock-screen transport controls.
- **Now Playing** — artwork with **swipe-to-skip** and **double-tap-to-like** gestures, a live spectrum
  **waveform seek bar** with a scrub time bubble, shuffle & repeat, an output-device switcher, a radio
  button, and an **Up-Next drawer** that is a real queue manager: pull it up, reorder, remove, or tap any
  song to jump to it.
- **Mini-player** — a floating bar across the app that expands into Now Playing.
- **Gapless & crossfade** — volume-envelope fades between tracks.
- **Loudness normalization** — evens out volume across sources (`LoudnessEnhancer`).
- **Adaptive quality & Hi-Res mode** — stream quality follows current network conditions; an optional
  max-quality mode prefers Opus 160 kbps / 48 kHz over AAC 128 kbps, forces **32-bit float** PCM output,
  and Settings shows a live readout of what the current output path (DAC/headset) actually supports.
- **Automatic equalizer** — a per-song EQ curve: a genre baseline refined by measuring the track's own
  spectrum, applied mean-zero with boost trim. The manual equalizer remains available when it's off.
- **Synced & karaoke lyrics** — timed lyrics with word-by-word / letter-by-letter highlighting where a
  source carries that resolution (LRCLIB, NetEase, KuGou, Musixmatch — all keyless), falling back to
  line-synced or prose lyrics otherwise.
- **Canvas** — an animated cover behind Now Playing built from the song's own (muted) music video, with an
  anti-static filter that rejects still-image uploads; network/quality/battery policies live in Settings.
- **Instant transport** — resolved stream URLs are cached per track identity and the next queue item is
  prefetched, so skips, seeks and rewinds are near-instant.
- **Audio cache** — recently played songs replay from a local Media3 cache (keyed by track identity, never
  by URL) and play offline without re-resolving.
- **Resume after process death** — reopen the app and it returns to the last track at the exact second
  (identities only are persisted, never ephemeral stream URLs).

## Queue & radio

- **Contextual Next/Prev** — navigation traverses whatever you started from: an album, an artist, a
  playlist, your Liked songs, Recently Played, Downloads, or the Local library.
- **Endless radio** — seed a radio from the current song or an artist; the queue auto-refills from a
  YouTube Music mix or a Deezer artist radio (the algorithm is selectable in Settings).
- **Shuffle** with faithful un-shuffle (restores the original order, even with duplicate entries).
- **Repeat** — off / one / all.

## Search

Tabbed search across sources:

- **Songs** — track search.
- **Artists** / **Albums** — dedicated entity search.
- **Playlists** — Deezer + YouTube playlists (Spotify playlists remain importable by URL).
- **Underground** — exclusive/independent material surfaced from YouTube (remixes, edits) and SoundCloud,
  grouped by source.
- **History & suggestion pills** — recent searches and artists you actually played come back as one-tap
  pills, computed entirely on-device (zero network, only deliberate searches are recorded).

## Home

A streaming-grade feed, rendered progressively from a disk cache so a warm start paints instantly:

- **Continue listening** speed dial (with a surprise-me die) on the overview tab.
- **Daily mixes** built from your own listening log (see Recommendations).
- **"Similar to …"** rows anchored on artists you play, a **mood/genre grid**, featured cards with
  preview, editorial playlists, charts, new releases, and mosaic tiles.
- A **feed source selector** — Deezer, Apple, SoundCloud editorial/charts, or a weighted blend.
- Rows announce themselves from local taste before any network call, so the layout doesn't jump while
  content fills in.

## Recommendations

- An **on-device listening log** (plays, completions, skips, listening time, time-of-day) feeds taste
  clusters and **three daily mixes** at a 70/30 familiar/discovery split.
- Optional **regional charts** personalization inferred from SIM/locale — asked in-app, no OS permission.
- Everything is computed locally; nothing about your listening leaves the device.

## Library

Tabbed library: **All**, **Playlists**, **Liked**, **Recent**, **Downloads**, and **Local**, each with an
in-list filter bar (the visible, filtered list is exactly what plays).

- **Liked / favorites** — heart any track from anywhere; it round-trips through persistence and plays back
  from Liked.
- **User playlists** — create, edit, reorder; imported playlists become normal editable playlists.
- **Recently played** — recorded provider-agnostically as tracks play.

## Local music player

- **Scans the device library** (`MediaStore.Audio`) into Songs / Albums / Artists views, with sort options,
  an A–Z fast-scroll rail, per-row actions, and honest codec badges (FLAC/ALAC/… as claimed by the file).
- A **Files explorer** (Storage Access Framework) plays audio from any folder you pick — including places
  the media scan can't see — with **no permission at all**; local-only playlists round it out.
- Plays through the **same** Media3 pipeline as remote content (via `content://` URIs), so favorites,
  playlists, queue, and Recently Played all work for local files too.
- Permission: **`READ_MEDIA_AUDIO`** on Android 13+, the legacy `READ_EXTERNAL_STORAGE` below — requested
  contextually when you open the scan views; denial is non-fatal (Files and playlists keep working).

## Downloads & offline

- **Four download formats**, chosen in Settings or per-song from the player's ⋮ menu:
  - **Original** — the bytes as delivered (YouTube audio is M4A).
  - **Opus** — the best YouTube source repackaged losslessly from WebM into a real `.opus` (Ogg) file
    (Android 10+; the option doesn't exist below).
  - **MP3 320** — encoded on-device with a pure-Java LAME port (Android ships no MP3 encoder).
  - **FLAC** — true lossless when the community lossless source has the song (see Plugins).
- **Full embedded tags in every format** — cover art, artist, album, year: ID3v2+APIC for MP3, Vorbis
  comments + `METADATA_BLOCK_PICTURE` for Opus, a real PICTURE block for FLAC, `ilst` atoms for M4A. A
  downloaded file looks right in any player on any device.
- **Segmented, multi-connection downloading** for speed; a foreground service keeps a batch alive with an
  aggregate progress notification.
- **Save to the phone** — optionally publish each download into the shared `Music/Rizx` folder
  (MediaStore), visible to file managers and every other player. Asked once when your first download
  starts; per-row and bulk “save to phone” actions cover the backlog; deleting inside Rizx keeps the
  phone copy. On Android 8–9 this needs the legacy write permission, requested right at opt-in.
- Downloaded files are resolved from local storage **before** any network attempt — downloads are the
  offline library.

## Playlist import

- **By URL** — Spotify, YouTube / YouTube Music, and Deezer playlist links.
- **By file** — Nuclear-JSON exports and Exportify CSV files.
- **Complete imports** — Deezer playlists are paged to the end, YouTube beyond the first 100 tracks;
  Spotify's public embed caps at 100 tracks (the keyless limit, stated in the UI). Playlist covers come
  along, and missing per-track art is backfilled from Deezer.
- Imports are persisted and become normal, editable playlists. All import paths are **keyless** — Spotify
  is read via the public embed data, never a private API secret.

## Artist pages

- **Full paged discography** split by type (albums / singles & EPs / compilations), top tracks, similar
  artists, and a **Wikipedia bio** (validated against the live API so the wrong article never shows).
- Artist names are resolved to a **canonical profile** (follower-ranked among same-name candidates), so
  opening an artist from a YouTube-sourced song lands on the real catalogue page.

## Plugins

- A **sandboxed QuickJS runtime** can download and run real Nuclear plugins.
- The sandbox exposes only `fetch` (no DOM, no filesystem, no Android APIs), with per-call timeouts and
  per-plugin crash isolation — a misbehaving plugin can't take down the app.
- 13 of the 14 plugins in Nuclear's registry run as-is; desktop-only integrations are hidden rather than
  shown broken. A native **Plugins** screen shows each plugin's version, health, and an enable/disable
  toggle.
- The **community lossless (FLAC) source** is itself a plugin: the app ships as a generic plugin host and
  a fresh clone builds with **zero** bundled plugins (and no plugin section) by design.

## Settings

- Every option is functional and persisted (DataStore-backed): playback (crossfade/gapless,
  normalization, adaptive quality, Hi-Res), download format & save-to-phone, canvas policies, radio
  algorithm, feed source, lyrics provider, cache size, and more.
- **Data saver** — a Rizx-level switch (plus automatic metered-network detection, hotspots included) that
  drops cover sizes and stream quality.
- **App language** — System / English / Español / Português / Français; the whole UI is localized in all
  four.
- **Theme mode** — System / Light / Dark (System follows the device live).

## Design & theming

- **Material 3** with a custom brutalist / Nothing-OS-inspired visual language (dot-matrix numerals,
  monospace labels, tactile press feedback and semantic haptics).
- Warm **Paper (light)** and near-black **Ivory (dark)** themes — driven entirely by design tokens
  (`RizxTheme.colors`), with no hard-coded colors in screens.
- **Responsive** — phones stay portrait; tablets and unfolded foldables get landscape and a two-pane
  Now Playing.
- Real cover art everywhere via Coil, with tinted procedural fallbacks when art is missing.

## Device compatibility

Rizx runs on **Android 8.0+ (API 26)**. The app was built API-first against modern Android, and every
newer-API nicety degrades gracefully behind a version check:

| Capability | Needs | On older devices |
|---|---|---|
| Opus download format | Android 10 (API 29) | Option hidden; downloads keep their original container |
| Save-to-phone without a permission | Android 10 (API 29) | Android 8–9 ask the legacy write permission at opt-in |
| System output-switcher panel | Android 10 (API 29) | Bluetooth settings open directly |
| Genre read from local files (AutoEQ hint) | Android 11 (API 30) | Genre skipped; AutoEQ measures the audio instead |
| System splash screen, blur halo | Android 12 (API 31) | Plain launch field; no halo |
| OS-owned per-app language | Android 13 (API 33) | The in-app selector applies and persists it itself |
| Rich haptic semantics | Android 13–14 | Nearest classic haptic constants |

## Content sources

All streaming and metadata come from **keyless** sources: Deezer, Audius, iTunes/Apple (search, RSS
editorial and charts), YouTube and SoundCloud (via NewPipeExtractor), LRCLIB / NetEase / KuGou /
Musixmatch for lyrics, Wikipedia for artist bios, and a community FLAC index via plugin. No API keys or
secrets ship in the app — and access controls are respected: Spotify *search* is deliberately absent
(its token gate is an anti-bot control, not public data), while Spotify *playlists* stay importable
through the public embed. See [PROVIDERS.md](PROVIDERS.md) for exactly what each source provides and how.
