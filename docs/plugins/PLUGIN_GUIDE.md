# Writing a Rizx plugin

_Reviewed against Rizx 1.0.0 and plugin API v1 on 2026-08-25._

A Rizx plugin is a small JavaScript package that registers one or more **providers** — a catalogue to
search, a source of audio, a lyrics service, a chart feed, a playlist importer. Rizx runs it in a
sandboxed QuickJS engine and treats everything it returns as untrusted data.

This document is the contract. It is written for people; there is a companion written for coding agents
at [PLUGIN_SPEC_FOR_AGENTS.md](PLUGIN_SPEC_FOR_AGENTS.md) with the same facts in imperative form.

Rizx runs plugins written for **Nuclear** unchanged — same `api`, same descriptors, same package layout.
Everything Rizx adds is additive and optional.

## What a plugin can and cannot do

A plugin gets `fetch`, `console`, timers, base64, `URLSearchParams`, `TextEncoder`/`TextDecoder`, a
`crypto` subset (random bytes, HMAC, digests), a pure-JS `DOMParser`, per-plugin key/value storage, and
a bridge to Rizx's native YouTube extractor.

It does **not** get a filesystem, any Android API, a DOM, `eval` of host code, `require` of npm packages
at runtime, non-HTTP URL schemes, the user's library, the playback queue, or transport control. There is
no way to reach them; they are not bridged.

**One JS engine is shared by every installed plugin, on one thread.** Rizx isolates plugins from each
other — see [Security model](#security-model) — but they compete for the same thread, so a plugin that
blocks it blocks everybody. Keep work asynchronous and bounded.

## Quick start

A plugin is a zip with `package.json` at the root:

```
package.json
src/index.js
```

```json
{
  "name": "acme-lyrics",
  "version": "1.0.0",
  "description": "Lyrics from the ACME archive.",
  "author": "you",
  "main": "src/index.js",
  "nuclear": { "category": "lyrics" },
  "rizx": { "apiVersion": 1 }
}
```

```js
module.exports = {
  onLoad: function (api) {
    api.Providers.register({
      id: 'acme',
      kind: 'lyrics',
      name: 'ACME Lyrics',
      getLyrics: async function (track) {
        const q = encodeURIComponent(track.title + ' ' + (track.artists[0] || {}).name);
        const res = await fetch('https://api.example.com/lyrics?q=' + q);
        if (!res.ok) return null;
        const body = await res.json();
        return { lyrics: body.text };
      },
    });
  },
};
```

Install it: **Settings → Plugins → Store → Install from URL**, pasting a link to the zip. During
development, serve it from your machine — an emulator reaches the host at `http://10.0.2.2:<port>`, and
debug builds allow cleartext for that host only.

### The manifest

| Field | Meaning |
|---|---|
| `name` | Required. Normalized into the plugin id: lowercased, anything outside `[a-z0-9._-]` collapsed to `-`. `Acme Lyrics` installs as `acme-lyrics`. Ids that are only dots, or start with one, are rejected. |
| `version` | Shown in the Plugins screen. Also what a bundled-archive update compares against. |
| `main` | Entry module. The extension is optional; `src/index.ts`, `src/index.js`, `dist/index.js` all work. |
| `nuclear.category` | Store grouping only — **not** the provider kind. `nuclear.categories` (array) is also read. |
| `nuclear.displayName` | Friendlier name for the row. |
| `rizx.apiVersion` | Optional. The plugin API this plugin was written against. Rizx refuses to install a plugin declaring a version **newer** than it implements, with a message saying which. Omit it and the plugin is treated as a Nuclear plugin, exactly as before. Current version: **1**. |

TypeScript is supported (`.ts`/`.tsx`, transpiled at install). **npm dependencies are not**: any runtime
bare import other than `@nuclearplayer/plugin-sdk`, `@nuclearplayer/ui`, `react` or `react-dom` fails the
install with the offending specifier named. Ship a bundled build (esbuild/rollup) instead.

## Lifecycle

Rizx calls, in order, whichever of these the module exports. All may be `async`; all receive `api`.

| Hook | When |
|---|---|
| `onLoad(api)` | The module has been evaluated. Register providers here. |
| `onEnable(api)` | Right after load, and whenever the user re-enables the plugin. |
| `onDisable(api)` | The user turned the plugin off, or it was quarantined. |
| `onUnload(api)` | Before the module graph is dropped (disable, update, uninstall). |

A throw in `onLoad`/`onEnable` **fails the install**, and Rizx rolls it back completely — no half-installed
plugin, no orphaned provider.

## The `api` object

| Namespace | Surface |
|---|---|
| `api.Providers` | `register(descriptor) → uid`, `unregister(id)`. Scoped to your plugin: you cannot register under, or unregister, another plugin's id. |
| `api.Settings` | `register(defs)` (seeds defaults), `get(key)`, `set(key, value)`. Persisted; survives updates. |
| `api.Storage` | `get(key)`, `set(key, value)`, `remove(key)`. Same store, different scope — use it for caches. |
| `api.Http` | `fetch(url, init)` / `get(url, init)`, identical to the global `fetch`. Some plugins prefer an injected fetch. |
| `api.Logger` | The same object as `console`. Goes to logcat under the `JsPlugin` tag. |
| `api.Shell` | `openExternal(url)` — http(s) only, **foreground only**, rate-limited. See the rules below. |
| `api.Events` | `on(name, fn)` / `off(name, fn)`. **Nothing emits events yet**; subscribing is currently inert. |
| `api.Ytdlp` | `search(query)`, `getStream(idOrUrl)`, `getPlaylist(url)`, backed by Rizx's native YouTube extractor. There is no yt-dlp binary on Android. |

Values passed to `Settings`/`Storage` are JSON-serialized, so they must be JSON-representable.

### `fetch`

`http://` and `https://` only. Responses are **UTF-8 text** at the boundary — a plugin cannot read binary,
by design. A single response is capped at 10 MB and fails during the read, not after. Each call has a
30 s timeout. Request headers pass through as given.

### Timers

`setTimeout` and `setInterval` belong to your plugin and **stop when it is disabled or uninstalled**.
There are no global timers to reach around this with.

## Providers

`api.Providers.register(descriptor)` takes an object with `id`, `kind`, `name`, and the methods for that
kind. Methods are discovered by walking the prototype chain, so a class instance works as well as an
object literal. Capabilities can be declared with `searchCapabilities: ['tracks', 'artists', …]`.

**Your provider's identity in the app is `<pluginId>:<descriptorId>`**, derived by Rizx. You cannot
choose it, and it can never collide with a built-in provider.

### The six kinds

| `kind` | Methods Rizx calls | Timeout |
|---|---|---|
| `metadata` | `search`, or any of `searchTracks` / `searchArtists` / `searchAlbums` / `searchPlaylists`; optionally `fetchAlbumDetails`, `fetchArtistBio`, `fetchArtistTopTracks`, `fetchArtistAlbums`, `fetchArtistRelatedArtists` | 15 s |
| `streaming` | `searchForTrackV2(track)` or `searchForTrack(artist, title)`; `getStreamUrlV2(id)` or `getStreamUrl(id)`; optionally `losslessSearch(track)` | 20 s |
| `lyrics` | `getLyrics` or `fetchLyrics` | 15 s |
| `dashboard` | `fetchTopTracks`, `fetchTopArtists`, `fetchTopAlbums`, `fetchEditorialPlaylists`, `fetchNewReleases` | 20 s |
| `playlists` | `fetchPlaylistByUrl` or `fetchPlaylist`; optionally `matchesUrl` | 30 s |
| `discovery` | `getRecommendations` | 20 s |

**`discovery` bridges but nothing calls it.** Rizx's up-next engine is a fixed set, so a discovery
provider would install, look healthy, and never run — which is why the store hides that category. The
seam is kept for when the selector learns to read the registry.

Implement only what you support. A method you do not define is simply not called; there is no penalty
for a small provider.

### What to return

Every entity carries its own identity as `source: { provider, id, url? }`. Omit it and Rizx falls back to
your descriptor id plus the item's `id`/title, which is workable but less precise — **provide it**.

```js
// A track
{ title: "…", artists: [{ name: "…" }], durationMs: 214000,
  thumbnail: "https://…", source: { provider: "acme", id: "t-1" } }

// search() returns
{ artists: [...], albums: [...], tracks: [...], playlists: [...] }

// searchForTrackV2(track) returns an array of candidates
[{ id: "c-1", title: "…", durationMs: 214000, thumbnail: "…" }]

// getStreamUrl(candidateId) — called with the candidate's `id` string, returns one stream
{ url: "https://…", protocol: "https", mimeType: "audio/mp4", bitrateKbps: 128, durationMs: 214000 }

// getLyrics() returns a string, or
{ lyrics: "plain text", lines: [{ timeMs: 12340, text: "…" }] }
```

Tolerances worth knowing: `artist` (a string) is accepted instead of `artists`; `duration` is read as
seconds when it is below 10 000 and as milliseconds otherwise — prefer `durationMs`; artwork is taken
from the first of `thumbnail`, `coverImage`, `image`, `images`, `artwork`, `picture`, `coverArt`.

**A malformed item is dropped, not fatal.** One bad row never fails the whole result, and it never
reaches the UI. This is also why a shape mistake is invisible: you get fewer results, not an error. Log
what you return while developing.

## Distribution

| Route | How |
|---|---|
| **Nuclear's registry** | Get an entry merged into [`NuclearPlayer/plugin-registry`](https://github.com/NuclearPlayer/plugin-registry). Rizx reads it directly and operates no registry of its own. |
| **Your own registry** | Host a `plugins.json` of the same shape; the user adds its URL under Plugins → Store → Add a registry. User registries are never filtered. |
| **A link** | Any URL serving the zip: a release asset (`plugin.zip` is preferred by name), a GitHub zipball, or your own host. |

A GitHub zipball wraps everything in a `repo-sha/` directory; Rizx unwraps it when that directory is the
one holding `package.json`. Archives are capped at 20 MB compressed, 40 MB unpacked.

Installing over an existing plugin keeps its `settings.json` and `storage.json` — **but only when the
manifest `name` matches**. A different plugin that happens to normalize to the same id starts empty
rather than inheriting someone else's stored tokens.

## Rules

These are conditions of being listed and of being a good citizen on someone's phone.

**Keyless, and keyless means public.** Do not ship API keys, tokens or secrets — a plugin is distributed
as readable source, so a key in it is a published key. A public API, or a token a service publishes in
its own page, is fine. Defeating an access control is not: if a service added a check specifically to
keep non-browser clients out, working around it is out of scope for Rizx (see
[PROVIDERS.md](../PROVIDERS.md#content-sources)).

**Do not act against the user.**

- Send only what the request needs, to the service the plugin is for. Do not ship the user's queries,
  library or listening habits anywhere else.
- `openExternal` is for something the user just asked for. Rizx already refuses it when the app is
  backgrounded and rate-limits it; do not design around that.
- No unbounded polling. Cache. The user is paying for the data and the battery.
- `api.Ytdlp` runs on the user's IP and connection. Do not build a scraping farm out of it.
- No obfuscated or remotely-fetched code. Ship what you wrote.

**Licensing.** Rizx is GPL-3.0, but a downloaded plugin is **separate data** — transpiled and run at
runtime, not linked into the app — so your plugin may carry any licence you like (see
[LICENSING.md](../LICENSING.md)). Content you fetch stays under the terms of whoever serves it.

## Security model

Be precise about what the sandbox is, because the honest version is more useful than a reassuring one.

**What it guarantees.** No filesystem, no Android APIs, no non-HTTP schemes, no arbitrary intents. Your
plugin cannot read another plugin's settings or storage, register under another plugin's id or a
built-in provider's, unregister another plugin's providers, run another plugin's hooks, or replace the
runtime's own functions — the host bindings are captured out of reach and the shared state is private.
Everything a plugin returns is parsed defensively. A plugin that fails repeatedly is quarantined and
disabled, and one that is disabled cannot re-register itself.

**What it does not.** A plugin you install can fetch **any** host and send it anything it can see, and
what it can see includes the search queries and track titles that flow through it. There is no declared
permission model and no network allowlist. The shared QuickJS VM is capped at **128 MiB**, but the
binding has no way to interrupt JavaScript that is already running. An infinite loop wedges that engine
until you use *Restart the plugin engine* in the Plugins screen; restart abandons the wedged worker, which
the process can reclaim only when Android terminates it. **Installing a plugin is trusting its author.**

## Before you publish

- [ ] The zip has `package.json` at the root and the entry named by `main` exists.
- [ ] No runtime bare imports outside the four allowed specifiers — bundle your dependencies.
- [ ] Every registered method is one Rizx calls for that `kind` (see the table).
- [ ] Items carry `source: { provider, id }`, and durations are `durationMs`.
- [ ] Failures return empty/`null` rather than throwing; the plugin degrades instead of disappearing.
- [ ] No secrets in the source, and no access control worked around.
- [ ] Network calls are bounded and cached; nothing runs on a timer that does not need to.
- [ ] Installed on a device: it registers, it answers, and disabling it stops all of its activity.

## When something goes wrong

| Symptom | Cause |
|---|---|
| Install fails naming a specifier | A runtime npm import. Ship a bundled build. |
| Install fails with `entry '…' not found` | `main` does not match a file in the archive. |
| Install refused, "needs plugin API v*N*" | `rizx.apiVersion` is newer than this Rizx. |
| Row says **Down** | The health probe's call failed or exceeded 5 s. Often a slow upstream, not a broken plugin. |
| Row says **Quarantined** | Five consecutive failures. Re-enabling clears it. Fix the cause first. |
| Fewer results than expected | Items are being dropped by the mapper. Check `source`, `title`, and candidate `id`. |
| `plugin runtime busy` | Another plugin held the engine too long. Yours was not at fault and was not blamed. |
