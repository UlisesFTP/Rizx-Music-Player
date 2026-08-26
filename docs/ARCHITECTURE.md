# Architecture

_Current implementation snapshot: 2026-08-25 · app 1.0.0 · Room schema 7_

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
| **widget** | `widget/` | `domain` + Media3 session | Home screen widgets (`RemoteViews`); drive playback through a `MediaController`, never a player of their own. |
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
the domain model):

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

- **`PlaybackService : MediaSessionService`** owns the **single audio ExoPlayer** instance, the
  `MediaSession`, and the system media notification. ExoPlayer is **never** created or accessed inside a
  Composable.
- The UI drives playback through **`PlaybackController`** (a domain abstraction), which talks to the
  service via a `MediaController`. `MediaItem.mediaId` is the `QueueItem.id`.
- Extras handled in the service: gapless/crossfade (volume-envelope fade), the optional loudness boost
  (`LoudnessEnhancer`), adaptive stream quality by network conditions, optional **32-bit float PCM
  output** (Hi-Res mode), the **automatic equalizer** (a per-song curve over the platform `Equalizer`,
  from a genre baseline refined by the track's own measured spectrum), a **PCM tap**
  (`TeeAudioProcessor`) that feeds the Now Playing waveform without any `RECORD_AUDIO` permission, and
  **resume-after-process-death** (a filesystem session store, holding identities only — no ephemeral
  URLs — restores the last track at the exact second).
- Canvas is the intentional exception to “one player”: `CanvasPlaybackController` owns a second,
  **muted video-only** ExoPlayer. It never joins the audio session or resolves the queue's audio.
- Crossfade is a volume-envelope fade-out/fade-in, not overlapping playback from two audio players.
  The current normalization switch applies a fixed `LoudnessEnhancer` gain; it is not measured LUFS
  normalization.

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
- Queue mutation remains in-memory. A sanitized session snapshot restores items, current index and
  playback position after process death; Room is not the queue's source of truth.

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

`data/canvas/` resolves a muted video loop for the current song (Apple Music motion artwork, TIDAL
video covers, then the song's own music video via NewPipe — in that priority) behind a policy gate:
network type, quality cap, battery saver, per-source toggles, and an **anti-static filter** that rejects
uploads that are really still images. The player renders it on a `TextureView` beneath the artwork; playback audio never depends on
the canvas stream.

## Lyrics

A raced provider chain — LRCLIB, NetEase (`yrc`), KuGou (`krc`), Musixmatch (`richsync`),
lyrics.ovh — ranked confident-match first, then word-level beats line-level beats prose. Every
candidate passes `LyricsTrackMatcher`: version, language-of-recording, timed-duration and artist gates
reject a different recording outright (a Japanese re-recording, a live medley, a cover filed under the
original artist), and the remaining drift travels with the lyric as `Lyrics.matchScore` so a
word-timed lyric from a doubtful match cannot outrank a line-timed one from an exact lookup. LRCLIB's
single-row lookups are cross-checked against its search results when the row is Latin-only, so a
romanized upload is outvoted by the script most uploaders used. Results are cached at their achieved
tier only, so a degraded fallback never shadows a better source later, and the cache is versioned so
a matcher fix invalidates the answers the old matcher gave. The karaoke view runs on a smooth
interpolating clock rather than polling.

## Recommendations

`recently_played` in Room is a real listening log: plays, completions, skips, listened time, and
time-of-day buckets. From it the app derives taste clusters, three daily mixes (70/30
familiar/discovery), "Similar to …" rows, and radio seeding — all **on-device**; nothing about
listening behaviour leaves the phone.

## Smart 8D audio (adaptive spatialization)

```
decoded PCM → PcmTappingAudioSink (waveform · AutoEQ · spatial analyzer)
            → SpatializingAudioSink (SmartSpatialEngine)
            → DefaultAudioSink → AudioTrack → session Equalizer
```

**It wraps the sink; it is not an `AudioProcessor`.** `DefaultAudioSink.configure` builds its
processing chain from a fixed list whenever the input is high-resolution, so anything passed to
`setAudioProcessors` is dropped on the float path — the path "prefer lossless" turns on. That has
already cost this app once (see `PcmTappingAudioSink`), and the spatializer would have failed the same
way, silently, on exactly the FLAC files it matters most for.

**The taps stay outside it**, so the waveform draws the recording and the automatic equaliser measures
the recording, rather than either of them seeing this effect and reacting to it.

- **`SmartSpatialEngine`** (`playback/spatial/`) is pure Kotlin — no Android imports, no allocation per
  frame, no locks — so the whole DSP is covered by JVM tests. A 24 dB/octave crossover keeps the bass
  centred, then equal-power panning, a fractional delay line for an interaural delay capped at 0.65 ms,
  head shadow, a front/back spectral cue, crossfeed, a six-tap ambience with a 42 ms pre-delay and
  allpass diffusion, level-neutral headroom and a stereo-linked limiter.
- **Every lateral cue is symmetric front-to-back.** Panning, interaural delay and head shadow all place
  a sound on the axis through the ears; none of them distinguishes ahead from behind, so an orbit built
  from those alone collapses to a line and half of it is wasted. The separating cue is spectral and
  belongs to the outer ear: a bell at 3.5 kHz swinging boost-to-cut across the orbit, plus a low-pass
  faded in as the source passes behind. **Height has no interaural cue either** — it rides a pinna notch
  swept from 6.3 to 10.8 kHz along an inclined ring — and the ambience leans with the source, since a
  fixed tail anchors the image and turns an orbit back into a sweep.
- **The mid and the side both travel, half a turn apart.** Only the middle of the high band used to
  move; the difference signal came back at a fraction of its level and sat still, so most of a wide
  record's width was discarded and what survived was an anchor. It now orbits opposite the centre with
  its own direction cues, and it moves by *balance* rather than by the pan law, which would have spent
  another 3–6 dB of the width on the movement. Splitting by frequency instead would tear one voice's
  body away from its consonants; mid/side separates instruments the mix had already separated. A disabled effect is bit-exact passthrough because the dry path
  is the untouched input, not a reconstruction.
- **Nothing audible may depend on the buffer size.** Media3 does not promise one, so anything that
  evolves over time — the orbit, how much the movement breathes with the level, and the glide towards a
  new profile — is stepped per fixed-length segment from a time constant, never once per `process` call.
  Two separate bugs came from getting this wrong; the second one meant a long buffer barely moved off
  the built-in defaults at all. The orbit's phase is likewise **accumulated** rather than computed from
  the stream position, which would leap most of a turn whenever the profile's period changed; it
  re-anchors on seek.
- **Subtractive filters are not filters.** `high = input − lowPass(input)` reconstructs the input
  exactly but rejects nothing, because a low-pass shifts phase as well as level: at 60 Hz under a 150 Hz
  cutoff the difference still carries most of the bass. It shipped in two places — the crossover, where
  it panned bass around the listener, and the reverb send, where it put bass into the most decorrelated
  part of the chain — and both are now real high-pass biquads.
- **`SpatializingAudioSink`** owns the buffer contract — one DSP pass per buffer even when the delegate
  takes it in pieces, the renderer's buffer never written, and the input advanced by exactly what the
  delegate consumed. Zero-copy bypass when off.
- **`SpatialTrackAnalyzer`** is a third PCM tap; it keeps the channels apart (stereo width is what a
  downmix destroys) and does its transforms on `Dispatchers.Default`.
- **`SmartSpatialProfiles`** is a pure table keyed on `SoundGenre` — the same normalised enum the
  automatic equaliser already resolves to — followed by measured adaptation and one clamp at the exit
  that no path can skip. There is no strength setting: the table itself is the tuning.
- **`SmartSpatialController`** mirrors `AutoEqualizer`: attach/release from the playback service, nested
  `collectLatest` so a track change cancels the previous song's work, and two gates (built-in speaker,
  system spatializer) that leave the setting on and report a reason.

- **`SpatialRenderRepository`** writes standalone 8D MP3s, and is deliberately *not* a
  `DownloadFormat` value. The download index derives its key from `track.source.identityKey` on read, so
  one song can hold exactly one row — an 8D copy would have had to displace the ordinary download, and
  then choosing 8D would quietly change what "downloaded" plays. It gets its own store, and its own
  `TrackDownloader` pointed at its own folder: that class deletes everything in its directory its caller
  does not claim, so one shared folder would have each index sweep away the other's files at startup.
  The DSP enters as an `Mp3Encoder` **decorator**, which leaves the transcode loop shared with the plain
  MP3 download untouched and lands the mono→stereo case (live playback bypasses mono instead, because
  there the channel count was already announced to the audio sink). The render engine is a fresh
  instance, never the playback singleton, which is mid-song holding a profile and a room full of tail.

`SpatialAudioProfileStore` caches the **measurement**, not the finished profile, so retuning the
per-genre table still takes effect on songs already heard.

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

- **Room** — favorites, playlists (+ items), the recently-played listening log, recognition history and
  local-first sync bookkeeping — the outbox, the cursor and other devices' listening counters (v7;
  audio, fingerprints and resolved stream URLs are never stored).
  `exportSchema` is
  **on**: each version's schema JSON is committed under `app/schemas/`, and every version bump ships its
  `Migration` together with the new JSON (see [BUILD.md](BUILD.md#room-schemas)).
- **DataStore (Preferences)** — settings and small key/value state (enabled providers, playback resolver
  settings, etc.).
- **kotlinx.serialization** — `Track`/queue/session serialization (`TrackJson`), always stripped of
  transient stream state before writing.

## Optional account, sync and portable sharing

Room remains authoritative. Local playlist, favorite and taste mutations enqueue idempotent outbox
operations in the same local transaction (`sync_outbox`, coalesced per entity). `SyncRunner` drains
them in batches of 100 through **one PostgREST RPC call** (`rizx_sync`): a single server transaction,
serialized per account with an advisory lock, applies the operations, assigns monotonic revisions and
returns the changes since the client's cursor (pages of 500). Revisions therefore commit in order and a
pull can never skip one. The client keeps a recoverable snapshot before replacing a dirty playlist,
filters its own echo, and stores the cursor in Room only after a page is applied. Signing out stops
sync without deleting the local library.

`SyncScheduler` decides *when*: sign-in, app start, a debounce after local edits, foreground after a
quiet period, and a 6-hour WorkManager backstop. While the app is on screen it also holds a
**realtime invalidation channel** — a plain OkHttp WebSocket to the backend's Phoenix endpoint, joined
privately with the user's token — on which the server publishes *one* message per completed sync
(`revision`, `device_id`). The message never carries data and never applies anything: it only makes
the client pull, and only when the revision is newer than its cursor and came from another device. The
socket closes in the background and degrades silently (bounded reconnect backoff, a fixed list of
refusals that stop it until the next foreground), so nothing depends on it.

Listening taste syncs **per device**: each installation publishes its own counters under a device id
and reads everyone else's into `taste_contributions`; the app sums them on read, so no device ever
overwrites another's history.

Google Credential Manager or a six-digit email OTP creates an optional permanent account. Tokens are
encrypted with Android Keystore-backed AES-GCM. Guest users are created only when an unlisted cloud
share needs ownership. Portable JSON/XSPF/M3U8 and every cloud payload are sanitized before crossing the
device boundary: local paths, local URIs and ephemeral stream candidates are excluded.

The Android client contains only *publishable* configuration (project URL, publishable key, Google web
client id, share base URL), injected at build time and absent from the repository. The backend — a
Postgres schema with row-level security, the `rizx_sync` RPC and the `playlist-shares`, `guest-claim`
and `account-delete` Edge Functions — is a separate deployment that communicates with the app over
HTTPS; it is not part of this repository or of the app's Corresponding Source. Without that
configuration the account features are hidden and every other feature works.

## Home screen widgets

`widget/` renders three `RemoteViews` widgets (a 4×2 card, a 4×1 bar and a 2×2 Audio ID card) from a
`WidgetSnapshot`: `PlaybackService` pushes one on every transition, play/pause change, seek and a
5-second ticker, and the updater falls back to the on-disk playback snapshot when the service is not
running — so the widgets show the last song with the app closed. Taps are broadcast to a
`WidgetActionReceiver`, which connects a `MediaController` to the session on demand (there is no
second player); the red dotted bar is 24 tap zones over one bitmap, each mapped to a seek fraction.
The microphone deep-links into the Audio ID screen already listening; the widget's play button hands a
recognized track to the normal `PlaybackController`. Titles are drawn into bitmaps with the app's
dot-matrix face because `RemoteViews` cannot load a custom typeface.

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
the listening log survive it. It covers 4 → 5, 5 → 6 and 6 → 7. A migration bug is unrecoverable by
the time a user notices, so from v5 onward every version bump ships its migration, its schema JSON and
its test together. The sync engine is driven end to end on the JVM against an in-memory replica of the
server RPC, and the invalidation socket against `MockWebServer`.
