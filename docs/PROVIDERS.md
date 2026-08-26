# Providers

_Current provider inventory: 2026-08-25 · Rizx 1.0.0 · native providers plus plugin API v1_

Rizx separates **metadata** (what to play) from **streaming** (how to play it), and registers both kinds in
a single registry. Every source is **keyless** — no API keys, tokens, or secrets ship in the app.

## The two contracts

### `MetadataProvider`

Searches a catalog and optionally fetches richer detail. Capability-gated — a provider declares what it
supports and only implements those methods.

```kotlin
interface MetadataProvider : ProviderDescriptor {
    val searchCapabilities: Set<SearchCapability>
    suspend fun search(params: SearchParams): SearchResults

    val detailCapabilities: Set<DetailCapability> get() = emptySet()
    suspend fun albumDetail(source: ProviderRef): Album? = null
    suspend fun artistDetail(source: ProviderRef): Artist? = null
    suspend fun radioTracks(seed: Track): List<Track> = emptyList()
    suspend fun playlistTracks(source: ProviderRef): List<Track> = emptyList()
}
```

### `StreamingProvider`

Finds playable sources for a `Track` and resolves concrete, ephemeral stream URLs, two-phase:

```kotlin
interface StreamingProvider : ProviderDescriptor {
    suspend fun searchForTrack(track: Track): List<StreamCandidate>   // phase 1 — discover
    suspend fun getStreamUrl(candidate: StreamCandidate): Stream      // phase 2 — resolve (ephemeral)
}
```

Metadata and streaming are **never collapsed** into one contract: the app can search Deezer's catalog but
play the matched track from Audius or YouTube.

## The registry

`ProviderRegistry` holds providers keyed by `ProviderKind` (`METADATA`, `STREAMING`, `LYRICS`, `DASHBOARD`,
`PLAYLISTS`, `DISCOVERY`). Streaming providers are single-active (one resolves playback at a time);
metadata/playlist searches can fan out across several sources and merge.

**Genre browsing is the one dashboard section that does not blend.** A genre id belongs to a single
catalogue, so `DashboardRepository.genreFeed` takes the first *enabled* provider declaring
`GENRE_FEED` that recognises the id, rather than merging two providers' unrelated groupings. Providers
that do not own the id space answer empty and are skipped — which also makes a failure indistinguishable
from "not mine", exactly as it should be.

**Failure isolation is a hard rule:** a provider that errors or times out must fail on its own and never
crash the app. Repositories degrade gracefully — if one source is down, the others still return results.

## Content sources

| Source | Kind(s) | Role | How (keyless) |
|---|---|---|---|
| **Deezer** | Metadata · Dashboard · Playlists | Catalog search (tracks/artists/albums/playlists), charts & editorial feed, **per-genre charts** behind Search's browse wall, artist radio & similar artists, full paged discographies | Public Deezer API |
| **Audius** | Streaming | **Full-length** track streaming | Public Audius API (discovery nodes) |
| **Apple / iTunes** | Metadata · Dashboard | Search & 30-second previews, 50-item RSS charts, Top-100 and editorial playlists (deduplicated to 60) | Public iTunes Search API + public RSS/browse endpoints |
| **YouTube / YT Music** | Streaming · Playlists · Discovery · Dashboard | Full-length extraction, playlist import/search, public songs/artists charts, music-album and music-playlist discovery, mixes and similar rows | NewPipeExtractor + public YouTube Charts (no API key) |
| **SoundCloud** | Streaming · Dashboard | Independent/underground tracks and the public **New & hot** 50-song chart | NewPipeExtractor |
| **Spotify** | Playlists · Dashboard | Full playlist import, eight public editorial/chart collections and album metadata/details derived from public Top-50/embed data | Public embed data + the anonymous bearer that page publishes (no private secret) |
| **LRCLIB** | Lyrics | Line-synced (timed) lyrics | Public LRCLIB API |
| **NetEase · KuGou** | Lyrics | Word-level karaoke lyrics (`yrc` / `krc`) | Public endpoints |
| **Musixmatch** | Lyrics | Word-level `richsync` lyrics | Public web token fetched at runtime — nothing ships in the app |
| **lyrics.ovh** | Lyrics | Prose fallback when nothing timed exists | Public lyrics.ovh API |
| **Wikipedia** | Metadata | Artist biographies, validated against the live API so the wrong article never shows | Public MediaWiki API |
| **Community lossless index** | Streaming (lossless) | True-FLAC sources for downloads and Hi-Res playback | Via **plugin** — the repository bundles no index |
| **Shazam-compatible recognition** | *(not a registry provider)* | Identifies ambient audio from a fingerprint computed on the device | Unofficial keyless endpoint — no key, no account, and the app identifies itself honestly rather than imitating a device |
| **Apple Music · TIDAL · YouTube** (canvas) | *(not a registry provider)* | Animated covers: Apple motion artwork, TIDAL video covers, the song's own music video as a muted fallback — in that priority | Public page data and embed tokens; per-source toggles |
| **Your own backend** (optional) | *(not a content provider)* | Account, cross-device sync of playlists/favorites/taste, unlisted share links | A Supabase project you deploy; the app only ships its publishable coordinates and works fully without it |

**Recognition is deliberately outside the registry.** `ProviderRegistry` models interchangeable
catalogues — one active, the rest as fallbacks — and its `ProviderKind` enum is mirrored by the plugin
bridge. A single fingerprinting service is neither of those, so it sits behind its own injected
`RecognitionProvider` contract instead (see
[ARCHITECTURE.md § Music recognition](ARCHITECTURE.md#music-recognition)). It remains just as
replaceable, without dragging the plugin subsystem into a feature about microphones. The endpoint is
undocumented and unsupported: when it changes, recognition degrades to an ordinary error and nothing
else is affected.

Notes:

- **Native, not a plugin:** full YouTube audio is a **native** provider built on
  [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — it extracts stream URLs directly,
  with no API key and no browser. (NewPipeExtractor is GPL-3.0, the same licence as this app.)
- **Keyless means public, not merely reachable.** A public API or a token published in a page is fine.
  Defeating an access control is not: Spotify's search endpoint is gated by an obfuscated anti-bot
  token, so Spotify **search is deliberately absent** — Spotify appears through public editorial/chart
  playlists and playlists you can **import by URL**, read from public embed data. Album cards derived
  from those charts resolve through the public album embed. Imports cover playlists of any length: past
  the embed's 100 rows it pages through the same gateway the web player uses, carrying the anonymous
  bearer the embed itself publishes. The line is drawn at *access controls*: data a page hands to any
  anonymous visitor is public; a token whose only purpose is to keep automated clients out is not,
  and defeating it is out of scope no matter how easy.
- **Import limits, per source.** Deezer, Spotify and YouTube/YT-Music all page to the same 10,000-track
  ceiling the library applies when saving; Apple Music's playlist page carries its whole tracklist in one
  response. When a source genuinely cuts a list short, the playlist says so on its own screen rather than
  quietly presenting a partial import as a complete one.
- **Fakes precede reals:** the codebase keeps `Fake*` metadata/streaming providers (`FakeMetadataProvider`,
  `FakeStreamingProvider`, …) used to build and test each vertical slice before wiring the real source.

## The plugin runtime

Beyond the native providers, Rizx includes a **sandboxed QuickJS runtime** (`data/plugin/`) that can
download and run real Nuclear JavaScript plugins:

- The sandbox exposes **`fetch`** for I/O plus a pure-JS `DOMParser` for scraper-style plugins — no DOM,
  no filesystem, no Android APIs.
- Every provider call goes through **one invoker** carrying a per-call timeout and a per-plugin
  quarantine counter, so a misbehaving plugin degrades alone and can't take the app down.
- Five `ProviderKind`s are real seams — metadata, streaming, lyrics, dashboards and playlists — and a
  plugin serves them exactly like a native source; the active choice survives a restart. **`discovery`
  bridges but has no consumer**: up-next comes from a fixed set of engines, so plugins in that category
  are kept out of the store rather than installed inert. Home's "For you" rows, Search's Underground and
  Playlists tabs and canvas go to specific services by design, not through the registry.
- Every plugin call passes a **gate** that queues for the single JS engine and only then starts the
  call's timeout, so a slow plugin cannot manufacture failures for the others. A cancelled call (leaving
  a screen, a caller's own budget) is never counted against a plugin.
- **Plugins are isolated from each other** (ADR 0026): the host builds each plugin's `api` closed over
  its own id, keeps the shared state out of reach, derives every provider id as `<pluginId>:<descriptorId>`
  — so a plugin can never take a built-in provider's place — and guards its own entry points with a
  per-engine token. What the sandbox still does *not* do is restrict where a plugin may connect.
- Writing one: [plugins/PLUGIN_GUIDE.md](plugins/PLUGIN_GUIDE.md), or
  [plugins/PLUGIN_SPEC_FOR_AGENTS.md](plugins/PLUGIN_SPEC_FOR_AGENTS.md) if you are an agent.
- Plugins that expect YouTube tooling get it as a bridge backed by the native NewPipe provider — there
  is no external binary.
- The store is filtered in `PluginRegistryClient`, before an entry reaches the app: `REPLACED_BY_NATIVE`
  drops the six whose job a native provider already does, and `NOT_RUNNABLE` drops those with no way to
  reach the host (`scrobbling` is not a `ProviderKind`, and nothing emits playback events to the runtime).
  A user-added registry is never filtered. Already-installed plugins are dropped in the ViewModel, so the
  store only offers what you can act on. A native Plugins screen shows version, health, and an
  enable/disable toggle per plugin.
- The **community lossless (FLAC) source is itself a plugin**, and the repository deliberately bundles
  **zero** plugin archives: a fresh clone builds a generic plugin host (see
  [BUILD.md](BUILD.md#project-structure)). Bundled archives, when a build has them, install themselves at
  startup — once per archive, replaced when the build ships a newer version, never restored after the
  user uninstalls one.

## Adding a provider (sketch)

1. Implement `MetadataProvider` and/or `StreamingProvider` in `data/provider/`, with a Retrofit/OkHttp (or
   NewPipe) client under `data/remote/<source>/` and DTO↔domain mappers that never leak DTOs upward.
2. Emit stable `ProviderRef(provider, id)` identities; keep resolved stream URLs ephemeral.
3. Declare only the capabilities you actually implement.
4. Register it (its DI module) so it joins the `ProviderRegistry`.
5. Unit-test the mapper and provider (MockWebServer for HTTP), and ensure failures are isolated.
