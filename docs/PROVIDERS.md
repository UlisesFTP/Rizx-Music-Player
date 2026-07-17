# Providers

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

**Failure isolation is a hard rule:** a provider that errors or times out must fail on its own and never
crash the app. Repositories degrade gracefully — if one source is down, the others still return results.

## Content sources

| Source | Kind(s) | Role | How (keyless) |
|---|---|---|---|
| **Deezer** | Metadata · Dashboard · Playlists | Catalog search (tracks/artists/albums), charts & editorial home feed, playlist tracks | Public Deezer API |
| **Audius** | Streaming | **Full-length** track streaming | Public Audius API (discovery nodes) |
| **iTunes** | Metadata · Streaming | Search + **30-second previews** | Public iTunes Search API (Apple) |
| **YouTube** | Streaming · Playlists | Full-length audio extraction, playlist search/import | NewPipeExtractor (no API key) |
| **SoundCloud** | Streaming | Independent/underground tracks | NewPipeExtractor |
| **Spotify** | Playlists (import only) | Playlist import by URL | Public embed data (no private secret) |
| **lyrics.ovh** | Lyrics | Song lyrics | Public lyrics.ovh API |

Notes:

- **Native, not a plugin:** full YouTube audio is a **native** provider built on
  [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — it extracts stream URLs directly,
  with no API key and no browser. (NewPipeExtractor is GPLv3, compatible with this app's AGPL-3.0.)
- **Spotify is import-only:** keyless Spotify *search* isn't feasible, so Spotify appears as a playlist you
  can **import by URL**, read from public embed data — never a private/TOTP secret.
- **Fakes precede reals:** the codebase keeps `Fake*` metadata/streaming providers (`FakeMetadataProvider`,
  `FakeStreamingProvider`, …) used to build and test each vertical slice before wiring the real source.

## The plugin runtime

Beyond the native providers, Rizx includes a **sandboxed QuickJS runtime** (`data/plugin/`) that can
download and run real Nuclear JavaScript plugins:

- The sandbox exposes only **`fetch`** — no DOM, no filesystem, no Android APIs.
- Per-call timeouts and **per-plugin crash isolation** keep a misbehaving plugin from affecting the app.
- Desktop-only Nuclear plugins that genuinely can't run on Android are hidden rather than shown broken.

This is a personal-use capability layered on top of the native providers, not a replacement for them.

## Adding a provider (sketch)

1. Implement `MetadataProvider` and/or `StreamingProvider` in `data/provider/`, with a Retrofit/OkHttp (or
   NewPipe) client under `data/remote/<source>/` and DTO↔domain mappers that never leak DTOs upward.
2. Emit stable `ProviderRef(provider, id)` identities; keep resolved stream URLs ephemeral.
3. Declare only the capabilities you actually implement.
4. Register it (its DI module) so it joins the `ProviderRegistry`.
5. Unit-test the mapper and provider (MockWebServer for HTTP), and ensure failures are isolated.
