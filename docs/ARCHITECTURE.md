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
  (`LoudnessEnhancer`), adaptive stream quality by network conditions, and **resume-after-process-death**
  (a filesystem session store, holding identities only — no ephemeral URLs — restores the last track at the
  exact second).

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

## Persistence

- **Room** — structured local data.
- **DataStore (Preferences)** — settings and small key/value state (enabled providers, playback resolver
  settings, etc.).
- **kotlinx.serialization** — `Track`/queue/session serialization (`TrackJson`), always stripped of
  transient stream state before writing.

## Testing

Unit tests live in `app/src/test/` (JVM, no device) using JUnit4 · MockK · Turbine · OkHttp MockWebServer.
High-value targets: `ProviderRegistry`, `MetadataRepository`, the streaming resolver, `QueueRepository`,
`FavoritesRepository`, `PlaylistRepository`, artwork selection, and `ProviderRef` identity. Pure mappers
(e.g. local-media and DTO mappers) are unit-tested without any Android dependency.
