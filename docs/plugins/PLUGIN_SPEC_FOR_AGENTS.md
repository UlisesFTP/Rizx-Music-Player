# Rizx plugin spec — for coding agents

_Current host contract: Rizx 1.0.0 · plugin API v1 · reviewed 2026-08-25._

Machine-oriented restatement of [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md). Same system, no prose. If you are
generating or reviewing a Rizx plugin, follow this document literally.

**Plugin API version: 1.** Rizx also runs Nuclear plugins unchanged.

## 0. Hard rules

**MUST**

- Ship a zip with `package.json` at the archive root and the entry file named by `main`.
- Bundle every dependency. The only bare specifiers that resolve at runtime are
  `@nuclearplayer/plugin-sdk`, `@nuclearplayer/ui`, `react`, `react-dom`. Any other runtime bare import
  **fails the install**.
- Register providers from `onLoad(api)` or `onEnable(api)`, using only the `api` object you were handed.
- Return the exact shapes in §4. A malformed item is dropped silently, so a shape error looks like "no
  results", never like an error.
- Return empty (`[]`, `null`) on failure. A throw is counted as a failure; five consecutive failures
  quarantine and disable the plugin.
- Keep every network call bounded and cached where the data allows.

**MUST NOT**

- Embed API keys, tokens or secrets. A plugin is distributed as readable source.
- Work around an access control a service added to exclude non-browser clients.
- Send user data (queries, titles, library, listening habits) anywhere other than the service the plugin
  exists to talk to.
- Call `api.Shell.openExternal` without a user action that just happened.
- Poll on a timer without a bound, or use `api.Ytdlp` as a general scraping backend.
- Fetch and evaluate code at runtime, or obfuscate the shipped source.
- Attempt to reach `globalThis.__rizx`, any `__rizx_*` binding, or another plugin's data. These are
  removed or guarded; the attempt is a bug in your plugin, not a route.

## 1. Manifest

```json
{
  "name": "acme-lyrics",
  "version": "1.0.0",
  "description": "…",
  "author": "…",
  "main": "src/index.js",
  "nuclear": { "category": "lyrics" },
  "rizx": { "apiVersion": 1 }
}
```

- `name` → plugin id: lowercase, `[^a-z0-9._-]+` → `-`, trimmed of `-`. **Rejected** if the result is
  empty, starts with `.`, or contains a path separator.
- `nuclear.category` is a store label. It is **not** the provider kind. Categories `scrobbling` and
  `discovery` are hidden from the store because Rizx cannot dispatch them.
- `rizx.apiVersion` is optional. If present and greater than the host's, the install is refused. Omit it
  for a Nuclear-compatible plugin.
- `main` may omit the extension. `.ts`/`.tsx` are transpiled at install time.

## 2. Lifecycle

```js
module.exports = {
  onLoad:    async function (api) {},  // register here
  onEnable:  async function (api) {},
  onDisable: async function (api) {},
  onUnload:  async function (api) {},
};
```

All hooks optional, all may be async, all receive `api`. A throw in `onLoad`/`onEnable` fails the install
and it is rolled back entirely.

## 3. `api` surface

```
api.Providers.register(descriptor) -> "<pluginId>:<descriptorId>"
api.Providers.unregister(id)                     // scoped to this plugin
api.Settings.register(defs) | .get(key) | .set(key, value)
api.Storage.get(key) | .set(key, value) | .remove(key)
api.Http.fetch(url, init) | .get(url, init)      // same as global fetch
api.Logger                                        // === console
api.Shell.openExternal(url)                       // http(s), foreground only, rate-limited
api.Events.on(name, fn) | .off(name, fn)          // NOTHING EMITS YET — inert
api.Ytdlp.search(q) | .getStream(idOrUrl) | .getPlaylist(url)
```

Globals: `fetch`, `console`, `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval`, `btoa`,
`atob`, `URLSearchParams`, `TextEncoder`, `TextDecoder`, `crypto.getRandomValues`, `crypto.randomUUID`,
`crypto.subtle` (HMAC/digest), `DOMParser`.

Absent by design: filesystem, Android APIs, DOM/`window`/`document`, `require` of npm, `XMLHttpRequest`,
WebSocket, non-HTTP schemes, `process`, global timers not owned by a plugin.

`fetch` constraints: `http://`/`https://` only; response body is **UTF-8 text** (binary is unreadable by
design); 10 MB cap enforced during the read; 30 s timeout.

Timers are per-plugin and stop on disable/uninstall.

## 4. Descriptors and return shapes

```js
api.Providers.register({
  id: 'acme',             // REQUIRED, non-blank
  kind: 'metadata',       // REQUIRED, one of the six below
  name: 'ACME',
  searchCapabilities: ['tracks', 'artists', 'albums', 'playlists', 'unified'], // optional
  /* methods for the kind */
});
```

Methods are found by walking the prototype chain, so class instances work.

**INVARIANT** — the provider's id in the app is `"<pluginId>:<descriptorId>"`, derived host-side. A `uid`
in your descriptor is ignored. A plugin provider therefore always contains `:` and can never collide
with a built-in provider id.

### Methods by kind

| kind | methods (first present is used) |
|---|---|
| `metadata` | `search(params)` and/or `searchTracks` / `searchArtists` / `searchAlbums` / `searchPlaylists`; optional `fetchAlbumDetails(ref)`, `fetchArtistBio(ref)`, `fetchArtistTopTracks(ref)`, `fetchArtistAlbums(ref)`, `fetchArtistRelatedArtists(ref)` |
| `streaming` | `searchForTrackV2(track)` \| `searchForTrack(artist, title)` — **note the different arities**; `getStreamUrlV2(candidateId)` \| `getStreamUrl(candidateId)`; optional `losslessSearch(track)` |
| `lyrics` | `getLyrics(track)` \| `fetchLyrics(track)` |
| `dashboard` | `fetchTopTracks()`, `fetchTopArtists()`, `fetchTopAlbums()`, `fetchEditorialPlaylists()`, `fetchNewReleases()` — all no-arg |
| `playlists` | `fetchPlaylistByUrl(url)` \| `fetchPlaylist(url)`; optional `matchesUrl(url)` returning a boolean |
| `discovery` | `getRecommendations(context)` — **registered but never called**; do not build one |

### Shapes

```js
// ProviderRef — attach to every entity you return
source: { provider: "acme", id: "t-1", url: "https://…" }   // url optional

// Track
{ title, artists: [{ name, source? }], durationMs, thumbnail, source }

// ArtistRef / AlbumRef / PlaylistRef
{ name | title, thumbnail, source }

// metadata search() ->
{ artists: [], albums: [], tracks: [], playlists: [] }       // any subset

// streaming searchForTrackV2(track) / searchForTrack(artist, title) ->
[{ id, title, durationMs, thumbnail, source }]               // id REQUIRED

// streaming getStreamUrl(candidateId) — argument is the candidate's `id` STRING ->
{ url, protocol: "https"|"http"|"hls"|"file", mimeType, bitrateKbps, durationMs, source }

// streaming losslessSearch(track) — optional; a provider with ONLY this is kept out of the
// ordinary streaming chain and used solely as a lossless index ->
[{ song, artist, url, album, durationMs, isrc, sha256 }]

// lyrics getLyrics() -> a string, or
{ lyrics | plain | text | body, lines: [{ timeMs, text }] }

// playlists fetchPlaylist() ->
{ title, tracks: [ /* Track */ ], source }
```

Accepted alternatives: `artist` (string) instead of `artists`; `name` instead of `title`; artwork from
the first present of `thumbnail`, `coverImage`, `image`, `images`, `artwork`, `picture`, `coverArt`.
`duration` below 10 000 is read as **seconds**, otherwise as milliseconds — always prefer `durationMs`.

Dropped-if-missing: a track without `title`; a candidate without `id`; a lyrics object with neither text
nor lines.

## 5. Failure semantics

| Situation | Result |
|---|---|
| Method returns `[]`/`null` | Treated as "nothing found". Not a failure. |
| Method throws | One failure recorded against the plugin. |
| Method exceeds its timeout (§ kind table) | One failure recorded. |
| Call cancelled by the host (user navigated away, probe budget) | **Not** recorded against you. |
| Engine busy with another plugin | `plugin runtime busy`. **Not** recorded against you. |
| 5 consecutive failures | Quarantined: providers unregistered, plugin disabled, reason shown. |
| Malformed item in an otherwise valid response | That item dropped; the rest kept. |

## 6. Template

```js
'use strict';

const CACHE_KEY = 'index';
const TTL_MS = 60 * 60 * 1000;

function makeProvider(api) {
  return {
    id: 'acme',
    kind: 'metadata',
    name: 'ACME',
    searchCapabilities: ['tracks'],

    async search(params) {
      const query = String((params && params.query) || '').trim();
      if (!query) return { tracks: [] };
      try {
        const res = await fetch('https://api.example.com/search?q=' + encodeURIComponent(query), {
          headers: { Accept: 'application/json' },
        });
        if (!res.ok) return { tracks: [] };
        const body = await res.json();
        return {
          tracks: (body.results || []).map(function (r) {
            return {
              title: r.name,
              artists: [{ name: r.artist }],
              durationMs: r.duration_ms,
              thumbnail: r.cover,
              source: { provider: 'acme', id: String(r.id) },
            };
          }),
        };
      } catch (e) {
        api.Logger.warn('acme search failed', e && e.message);
        return { tracks: [] };   // degrade, never throw
      }
    },
  };
}

module.exports = {
  onLoad: function (api) {
    api.Settings.register([
      { id: 'endpoint', type: 'string', label: 'API endpoint', default: 'https://api.example.com' },
    ]);
    api.Providers.register(makeProvider(api));
  },
};
```

## 7. Self-check before publishing

Answer all of these yes.

1. Does the zip have `package.json` at its root, with `main` pointing at a file that exists in the zip?
2. Is every runtime import relative, or one of the four allowed bare specifiers?
3. Is `kind` one of the six, and is every method you defined in that kind's row?
4. Does every returned entity carry `source: { provider, id }`, and are durations in `durationMs`?
5. Does every method return empty/`null` on failure instead of throwing?
6. Are there zero secrets, keys, tokens or obfuscated blobs in the source?
7. Is every network call bounded, cached where possible, and aimed only at the service this plugin is for?
8. Does the plugin do nothing at all once disabled — no timers, no fetches?
9. If you targeted a Rizx-specific behaviour, did you declare `rizx.apiVersion`?
10. Did you install it on a device and confirm it registers and returns results?

## 8. Facts an agent commonly gets wrong

- The plugin id is derived from `name`, not chosen. Do not assume `descriptor.id` is the app-wide id.
- `nuclear.category` is not `kind`. A `lossless` category can register a `streaming` provider.
- Dashboard methods take **no arguments**; limits are applied by the host afterwards.
- `api.Events` compiles and runs but never fires. Do not build a feature on it.
- `discovery` providers are never invoked. Do not write one.
- There is no `api.Player`, `api.Queue`, `api.Notifications`, `api.Downloads` or `api.i18n`.
- `fetch` cannot return binary. A plugin cannot inspect audio bytes.
- Returning `duration: 214` means 214 **seconds**; returning `duration: 214000` means milliseconds.
- `searchForTrack` takes `(artist, title)` as two strings — only `searchForTrackV2` takes a track object.
  Defining `searchForTrack(track)` compiles and silently searches for the wrong thing.
- `getStreamUrl` receives the candidate's `id` **string**, not the candidate object.
- Silence is the normal failure mode. If results are missing, suspect the shape, not the network.
