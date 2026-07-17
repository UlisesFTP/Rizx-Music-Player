# Licensing & attribution

## App license: AGPL-3.0

Rizx Player is licensed under the **GNU Affero General Public License, version 3.0 (AGPL-3.0)**. The full
text is in [`LICENSE`](../LICENSE); the attribution/summary is in [`NOTICE`](../NOTICE).

## Upstream: Nuclear

Rizx is an **independent, clean-room reimplementation** of the *business logic* of
[**nukeop/nuclear**](https://github.com/nukeop/nuclear) — the open-source desktop music player, itself
AGPL-3.0. Rizx re-implements Nuclear's domain concepts (providers, queue, two-phase stream resolution,
playback, favorites, playlists) on native Android; it is **not** a port of Nuclear's React/Tauri desktop
UI, and is **not affiliated with or endorsed by** the Nuclear project.

As a derived work, Rizx carries the full AGPL obligations independently: the license text, this
attribution, the Corresponding Source, and a log of major modifications are preserved and distributed with
every build.

## Corresponding Source (AGPL §6)

The complete Corresponding Source for each distributed build is this public Git repository, tagged to match
the build's `versionName` / `versionCode`. The in-app **About** screen links back to it. Build
configuration needed to reproduce a build (Gradle) is included; **signing keys and secrets are not**.

## Remote interaction (AGPL §13)

Rizx is a standalone client with **no backend or server component** operated by the authors, so §13 is not
triggered. If a server/remote component is ever added (hosted backend, remote-control API, cast/MPD server,
etc.), §13 activates and that component must offer its Corresponding Source to remote users.

## Major modifications relative to upstream Nuclear

- Complete native Android reimplementation (Kotlin · Jetpack Compose · Material 3 · Navigation Compose)
  replacing the React/Redux/Electron(Tauri) desktop UI.
- Media3 / ExoPlayer + `MediaSessionService` background playback replacing the Web Audio / MSE / hls.js web
  streaming stack.
- Room + DataStore persistence replacing the desktop store.
- Provider architecture (separate metadata + streaming providers, a registry, two-phase ephemeral stream
  resolution) re-modeled from upstream `packages/model` and `plugin-sdk`.
- Native, keyless full-length YouTube audio via NewPipeExtractor; a sandboxed QuickJS plugin runtime for
  real Nuclear plugins (personal-use capability).

## Third-party components

Bundled open-source libraries and their licenses are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) and surfaced in-app via **About → Open-source
licenses**. Highlights:

- Most AndroidX / Compose / Media3 / Hilt / Room / Retrofit / OkHttp / Coil dependencies are **Apache-2.0**.
- **NewPipeExtractor** is **GPLv3** — combinable with AGPL-3.0; the combined work is distributed under
  AGPL-3.0. Downloaded Nuclear plugins are separate *data* (their own licenses), transpiled and run at
  runtime, not linked into the APK.
- Bundled fonts (Space Grotesk, Manrope, Martian Mono, Doto) are licensed under the **SIL Open Font License
  1.1**.

## Content

Search results and audio are fetched at runtime from third-party services (Deezer, Audius, Apple's iTunes
Search API, YouTube, SoundCloud, lyrics.ovh) under **their** terms. **No** third-party API keys, code, or
assets are bundled in the app. See [PROVIDERS.md](PROVIDERS.md).

## Privacy

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
