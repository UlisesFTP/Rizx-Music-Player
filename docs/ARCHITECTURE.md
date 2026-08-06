# Architecture

Rizx Player is a single-module Android app (`fm.rizx.player`) built on a clean, one-directional layering.
This document describes the layers, the identity model, how streaming and playback work, and the queue —
the load-bearing parts of the design.

## Layers & dependency direction

```
UI (Compose)
   │  observes StateFlow, calls suspend fns
   ▼
ViewModel  ──►  UseCase  ──►  Repository / Controller
                                   │
                                   ▼
                 Provider · Room · DataStore · Media3
```

| Layer | Package | Depends on | Rule |
|---|---|---|---|
| **domain** | `domain/` | *nothing Android* | Pure Kotlin models, provider/repository contracts, use cases. No `android.*` imports. |
| **data** | `data/` | `domain` | Providers, remote clients (Retrofit/OkHttp/NewPipe), local stores (Room/DataStore), DTO↔domain mappers, repositories. |
| **playback** | `playback/` | `domain` + Media3 | `PlaybackService : MediaSessionService` owns the single ExoPlayer; the stream resolver and Media3 mappers live here. |
| **ui** | `ui/` | `domain` (via ViewModels/use cases) | Compose screens, theme tokens, navigation. Never touches a provider or ExoPlayer directly. |
| **core** | `core/` | — | Cross-cutting: error types, network monitor, cache, DI modules, formatting. |

The dependency arrow only ever points **inward**: `ui → domain`, `data → domain`, `playback → domain`.
`domain` never imports `data`, `ui`, Android, or Media3. Provider DTOs never leak past the mapper into a
domain model.

## Identity: `ProviderRef`

Every piece of upstream-derived content — track, album, artist, playlist — is identified by a
**`ProviderRef(provider, id)`**, e.g. `deezer:12345`, `youtube:dQw4w9WgXcQ`, `local:8801`.

```kotlin
class ProviderRef(val provider: String, val id: String, val url: String? = null) {
    val identityKey: String get() = "$provider:$id"   // url is deliberately excluded from equals/hashCode
}
```

- Identity is **`provider` + `id` only.** A `url` is carried for convenience but excluded from
  `equals`/`hashCode` — a ref that later gains or changes a URL is still the same entity.
- **Never** use a title, artist name, album, or URL as identity.
- Because identity is stable and serializable, a `Track` round-trips cleanly through persistence
  (`TrackJson`). That's why favorites, playlists, the queue, recently-played, and session restore all work
  uniformly across *every* source — remote providers and local files alike.

### The `Track` shape

A `Track` has **no `id`** — its identity *is* `source: ProviderRef`. Other notable shapes (aligned with
upstream Nuclear's `packages/model`):

- `artists` are full `ArtistCredit`s (name + roles + optional `source`), not plain strings.
- `album` is a lightweight `AlbumRef` (not a full album object).
- `artwork` is an `ArtworkSet` (multiple sizes), not a single URL.
- Durations are milliseconds (`durationMs`); timestamps are ISO-8601 strings (`…Iso`).
- `streamCandidates` is **transient** resolution state — `stripResolutionState()` drops it before
  persisting so ephemeral stream URLs are never stored.

## Providers: metadata vs streaming (kept separate)

Two provider contracts, deliberately **not** collapsed into one:

- **`MetadataProvider`** — searches a catalog; optionally fetches album/artist detail, radio seeds, and
  remote playlist tracks. Capability-gated (a provider declares `searchCapabilities` / `detailCapabilities`
  and only implements what it supports).
- **`StreamingProvider`** — finds playable sources for a `Track` and resolves concrete stream URLs.

Providers live in a **`ProviderRegistry`** keyed by `ProviderKind` (`METADATA`, `STREAMING`, `LYRICS`,
`DASHBOARD`, `PLAYLISTS`, …). A broken provider must **fail independently and never crash the app**;
repositories degrade gracefully when one source errors. See [PROVIDERS.md](PROVIDERS.md).

## Two-phase stream resolution (URLs are ephemeral)

Playing a `Track` never uses a stored URL. Resolution happens just-in-time in two phases:

```
Track ──phase 1──► List<StreamCandidate> ──phase 2──► Stream (concrete, ephemeral URL)
      searchForTrack()                    getStreamUrl()
```

1. **`searchForTrack(track)`** discovers candidate sources on the active streaming provider (matches are
   imperfect — usually several).
2. **`getStreamUrl(candidate)`** resolves one candidate to a concrete `Stream` right before playback.

The resolved `Stream.url` is **ephemeral** — it may expire and is **never persisted** in playlists,
favorites, or long-term cache. Only the identity (`ProviderRef`) is durable.

### `QueueStreamResolver` — the local-first seam

`QueueStreamResolver` sits in front of network resolution with a short-circuit:

```kotlin
library.localStream(item.track)          // on-device MediaStore file (content://)
    ?: downloads.localStream(item.track) // previously downloaded file (file://)
    ?: cachedFresh(track.source.identityKey) // a still-fresh cached stream URL
    // else → resolve over the network
```

Local files and downloads resolve to a `content://` / `file://` URL with `protocol = FILE` and play through
ExoPlayer's `DefaultDataSource` with **zero extra configuration**. `localStream` is a pure in-memory
lookup (it rebuilds the content URI from the id) so it is safe to call on the ExoPlayer loader thread and
never queries `MediaStore` there. A short-lived, `ProviderRef`-keyed cache plus next-track prefetch keeps
song changes and seeks near-instant.

## Playback

- **`PlaybackService : MediaSessionService`** owns the **single** real ExoPlayer instance, the
  `MediaSession`, and the system media notification. ExoPlayer is **never** created or accessed inside a
  Composable.
- The UI drives playback through **`PlaybackController`** (a domain abstraction), which talks to the
  service via a `MediaController`. `MediaItem.mediaId` is the `QueueItem.id`.
- Extras handled in the service: gapless/crossfade (volume-envelope fade), loudness normalization
  (`LoudnessEnhancer`), adaptive stream quality by network conditions, optional **32-bit float PCM
  output** (Hi-Res mode), the **automatic equalizer** (a per-song curve over the platform `Equalizer`,
  from a genre baseline refined by the track's own measured spectrum), a **PCM tap**
  (`TeeAudioProcessor`) that feeds the Now Playing waveform without any `RECORD_AUDIO` permission, and
  **resume-after-process-death** (a filesystem session store, holding identities only — no ephemeral
  URLs — restores the last track at the exact second).

## Queue

- **`PlaybackQueue`** = a flat `List<QueueItem>` + an integer `currentIndex` cursor + `repeatMode` +
  `shuffleOn` + a `QueueContext`.
- **`QueueItem.id`** is a per-insertion UUID, **distinct from `Track.source`** — the same track may appear
  multiple times; reorder/remove keep `currentIndex` valid.
- **`QueueContext`** records where the queue was started from (`QueueSourceKind`: `ALBUM`, `ARTIST`,
  `PLAYLIST`, `LIKED`, `RECENTS`, `DOWNLOADS`, `LOCAL`, `RADIO`, `MANUAL`). This drives **contextual
  Next/Prev** (traverse the album/artist/playlist you started from) and the **endless radio** auto-refill.
- **Shuffle** stores `unshuffledIds` (the pre-shuffle order) so toggling shuffle off restores the original
  order exactly, even with duplicate tracks.

## Downloads & the format pipeline

A download saves the resolved stream's bytes into app-private storage and indexes them by
`Track.source` — never by the ephemeral URL. On top of that sit optional, isolated steps:

```
fetch (segmented, multi-connection)
  → MP3 transcode        (MediaCodec decode → pure-Java LAME encode; only for the MP3 format)
  → Opus repackage       (WebM → Ogg Opus remux, same bitstream, no re-encode; API 29+)
  → tag write            (cover/artist/album/year embedded in M4A · MP3 · FLAC · Ogg Opus)
  → MediaStore export    (a copy into the shared Music/Rizx, only at the user's opt-in)
```

Every step is best-effort by design: a failed conversion, tag write, or phone-copy never turns a good
download into a failed one — the original bytes are already indexed and playable offline. FLAC comes
from the community lossless source when its plugin can serve the song; the Ogg Opus comment header is
written by an in-repo tagger (`OggOpusTagger`) because no bundled library can write that container.

## Canvas (animated covers)

`data/canvas/` resolves a muted video loop for the current song (the song's own music video via
NewPipe; Apple motion artwork where it exists) behind a policy gate: network type, quality cap, battery
saver, per-source toggles, and an **anti-static filter** that rejects uploads that are really still
images. The player renders it on a `TextureView` beneath the artwork; playback audio never depends on
the canvas stream.

## Lyrics

A ranked provider chain — word-level beats line-level beats prose: LRCLIB, NetEase (`yrc`), KuGou
(`krc`), Musixmatch (`richsync`), lyrics.ovh. Results are matched to the playing track (title/artist
plus a ±2 s duration window) and cached at their achieved tier only, so a degraded fallback never
shadows a better source later. The karaoke view runs on a smooth interpolating clock rather than
polling.

## Recommendations

`recently_played` in Room is a real listening log: plays, completions, skips, listened time, and
time-of-day buckets. From it the app derives taste clusters, three daily mixes (70/30
familiar/discovery), "Similar to …" rows, and radio seeding — all **on-device**; nothing about
listening behaviour leaves the phone.

## Music recognition

`microphone → PCM → fingerprint → service → RecognitionMatch → resolver → Track → normal playback`

Four seams, all behind `domain/recognition` contracts (`MicrophoneRecorder`, `RecognitionProvider`,
`RecognitionTrackResolver`, `RecognitionRepository`) so the backend is replaceable without the UI,
Room or the use case noticing. The recognition backend is deliberately **not** a `ProviderRegistry`
entry: that registry models interchangeable catalogues with one active and the rest as fallbacks, which
is not what a single fingerprinting service is, and its `ProviderKind` enum is mirrored by the plugin
bridge.

- **Capture** — `AndroidMicrophoneRecorder` asks for 16 kHz mono first, which is what the fingerprint
  wants and what every device supports for voice capture, so the audio HAL does the resampling and
  `Pcm16Resampler` (windowed-sinc, band-limited) is only needed on devices that refuse. Nothing is
  written to storage; cancelling releases the microphone immediately.
- **Fingerprint** — `ShazamSignatureGenerator` is a port of the algorithm documented by
  [SongRec](https://github.com/marin-m/SongRec): 2048-point FFT every 128 samples, Hann window, peak
  spreading across time and frequency, four bands, CRC32-framed binary. It has **no Android imports** —
  `java.util.Base64` rather than `android.util.Base64` — which is what lets the whole wire format be
  covered by JVM unit tests.
- **Request** — `ShazamRecognitionClient` derives from the shared `OkHttpClient` (same connection pool)
  minus the catalogue caches, and holds no policy; `ShazamRecognitionProvider` holds the policy: one
  request at a time, a floor between calls, bounded retries for transient failures only, and a
  five-minute memo keyed by the **SHA-256** of the fingerprint.
- **Resolution** — `DefaultRecognitionTrackResolver` tries ISRC (Deezer identity lookup), then Apple's
  `adamid` (iTunes lookup, verified), then a scored search via `RecognitionMatcher` — which reuses
  `RecordingIdentity` and `ArtistNameMatching`, the same primitives the artwork enricher and the lossless
  matcher use. Below threshold it returns `null` rather than a guess.
- **Session** — `RecognitionRepositoryImpl` is a singleton with its own supervised scope, so a rotation
  or a trip to the permission settings rejoins a capture in progress. Each session carries a generation
  number and may only publish state while it is still the current one, which is what stops a late answer
  from an abandoned attempt overwriting a newer one.

## Persistence

- **Room** — favorites, playlists (+ items), the recently-played listening log, and the recognition
  history (v5; audio and fingerprints are never stored). `exportSchema` is
  **on**: each version's schema JSON is committed under `app/schemas/`, and every version bump ships its
  `Migration` together with the new JSON (see [BUILD.md](BUILD.md#room-schemas)).
- **DataStore (Preferences)** — settings and small key/value state (enabled providers, playback resolver
  settings, etc.).
- **kotlinx.serialization** — `Track`/queue/session serialization (`TrackJson`), always stripped of
  transient stream state before writing.

## Testing

Unit tests live in `app/src/test/` (JVM, no device) using JUnit4 · MockK · Turbine · OkHttp MockWebServer.
Recognition is covered end to end there — fingerprint wire format, HTTP client against every status code
the service can return, the resolver ladder, and the session state machine — which is possible only
because the fingerprint and resampler carry no Android imports.
High-value targets: `ProviderRegistry`, `MetadataRepository`, the streaming resolver, `QueueRepository`,
`FavoritesRepository`, `PlaylistRepository`, artwork selection, `ProviderRef` identity, the download
format pipeline (transcode / remux / tag writing, including a from-scratch Ogg page-level tagger), and
the lyrics parsers/matchers. Pure mappers (e.g. local-media and DTO mappers) are unit-tested without any
Android dependency. Version-gated code keeps `Build.VERSION` checks in Android-only classes and
composables, so the JVM-tested pipeline never branches on SDK level.

Instrumented tests (`app/src/androidTest/`, device required) cover what the JVM cannot: the karaoke
lyrics timing screen, and **Room migrations** — `RizxMigrationTest` opens a database at the previous
version from its exported schema, runs the real `Migration`, and asserts that favorites, playlists and
the listening log survive it. A migration bug is unrecoverable by the time a user notices, so from v5
onward every version bump ships its migration, its schema JSON and its test together.
