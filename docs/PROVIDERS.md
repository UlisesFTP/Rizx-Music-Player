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
| **Deezer** | Metadata · Dashboard · Playlists | Catalog search (tracks/artists/albums/playlists), charts & editorial feed, artist radio & similar artists, full paged discographies | Public Deezer API |
| **Audius** | Streaming | **Full-length** track streaming | Public Audius API (discovery nodes) |
| **Apple / iTunes** | Metadata · Dashboard | Search & 30-second previews, editorial playlists and Top-100 charts | Public iTunes Search API + public RSS/browse endpoints |
| **YouTube / YT Music** | Streaming · Playlists · Discovery | Full-length audio extraction, playlist import/search, music-video canvas, YT Music mixes seeding radio & recommendations | NewPipeExtractor (no API key) |
| **SoundCloud** | Streaming · Dashboard | Independent/underground tracks, editorial feed | NewPipeExtractor |
| **Spotify** | Playlists (import only) | Playlist import by URL | Public embed data (no private secret) |
| **LRCLIB** | Lyrics | Line-synced (timed) lyrics | Public LRCLIB API |
| **NetEase · KuGou** | Lyrics | Word-level karaoke lyrics (`yrc` / `krc`) | Public endpoints |
| **Musixmatch** | Lyrics | Word-level `richsync` lyrics | Public web token fetched at runtime — nothing ships in the app |
| **lyrics.ovh** | Lyrics | Prose fallback when nothing timed exists | Public lyrics.ovh API |
| **Wikipedia** | Metadata | Artist biographies, validated against the live API so the wrong article never shows | Public MediaWiki API |
| **Community lossless index** | Streaming (lossless) | True-FLAC sources for downloads and Hi-Res playback | Via **plugin** — the repository bundles no index |

Notes:

- **Native, not a plugin:** full YouTube audio is a **native** provider built on
  [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — it extracts stream URLs directly,
  with no API key and no browser. (NewPipeExtractor is GPLv3, compatible with this app's AGPL-3.0.)
- **Keyless means public, not merely reachable.** A public API or a token published in a page is fine.
  Defeating an access control is not: Spotify's search endpoint is gated by an obfuscated anti-bot
  token, so Spotify **search is deliberately absent** — Spotify appears only as a playlist you can
  **import by URL**, read from public embed data.
- **Fakes precede reals:** the codebase keeps `Fake*` metadata/streaming providers (`FakeMetadataProvider`,
  `FakeStreamingProvider`, …) used to build and test each vertical slice before wiring the real source.

## The plugin runtime

Beyond the native providers, Rizx includes a **sandboxed QuickJS runtime** (`data/plugin/`) that can
download and run real Nuclear JavaScript plugins:

- The sandbox exposes **`fetch`** for I/O plus a pure-JS `DOMParser` for scraper-style plugins — no DOM,
  no filesystem, no Android APIs.
- Every provider call goes through **one invoker** carrying a per-call timeout and a per-plugin
  quarantine counter, so a misbehaving plugin degrades alone and can't take the app down.
- All six `ProviderKind`s bridge into the registry, so a plugin can serve metadata, streams, lyrics,
  dashboards, playlists or discovery exactly like a native source.
- Plugins that expect YouTube tooling get it as a bridge backed by the native NewPipe provider — there
  is no external binary.
- **13 of the 14 plugins** in Nuclear's registry run as-is; the desktop-only ones are hidden rather than
  shown broken. A native Plugins screen shows version, health, and an enable/disable toggle per plugin.
- The **community lossless (FLAC) source is itself a plugin**, and the repository deliberately bundles
  **zero** plugin archives: a fresh clone builds a generic plugin host (see
  [BUILD.md](BUILD.md#project-structure)).

## Adding a provider (sketch)

1. Implement `MetadataProvider` and/or `StreamingProvider` in `data/provider/`, with a Retrofit/OkHttp (or
   NewPipe) client under `data/remote/<source>/` and DTO↔domain mappers that never leak DTOs upward.
2. Emit stable `ProviderRef(provider, id)` identities; keep resolved stream URLs ephemeral.
3. Declare only the capabilities you actually implement.
4. Register it (its DI module) so it joins the `ProviderRegistry`.
5. Unit-test the mapper and provider (MockWebServer for HTTP), and ensure failures are isolated.
