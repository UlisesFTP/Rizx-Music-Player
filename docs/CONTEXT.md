# Rizx Player — project context

_State header verified against the working tree on 2026-09-13. The running log at the end is
append-only, newest last._

This is the public "where the project stands" document: the current state in one table, what is
implemented, what is optional, what is private, and a dated log of what changed and why. A
developer or coding agent reads the header, then the **tail** of the log, before touching code
([`../AGENTS.md`](../AGENTS.md) § Required bootstrap). The rules are in [`GOVERNANCE.md`](GOVERNANCE.md);
the map of the code is [`TECHNICAL_GUIDE.md`](TECHNICAL_GUIDE.md).

## State

| Area | Current state |
|---|---|
| App | Rizx Player **1.0.0** (`versionCode 3`), package `fm.rizx.player`, GPL-3.0 |
| Android | Kotlin 2.0.21 · Jetpack Compose (BOM 2024.12.01) · Hilt 2.52 · Media3 1.5.1 · `minSdk 26` · `targetSdk/compileSdk 36` |
| Build | Gradle 8.12 · AGP 8.9.1 · KSP 2.0.21-1.0.28 · JDK 21 to run Gradle (17–23 work, 25 does not) |
| Persistence | Room schema **7** (exported schemas 4–7) · four Preferences DataStores · JSON stores under `filesDir` |
| Size | 496 main Kotlin files · 202 JVM test files · 5 instrumented test files · 4 locales (en, es, pt, fr) |
| Verification | **1,695 JVM tests in 193 suites**, `lintDebug` 0 errors, `assembleDebug` and `assembleRelease` pass (2026-09-13) |
| Distribution | Signed APK on GitHub Releases; the app updates itself from `releases/latest` (spec 024) |
| Branches | `main` only. Rizx Web is a separate repository with `main` (web) and `electron` (desktop) lines |
| Optional backend | A Supabase project (auth, `rizx_sync` RPC, Realtime, Edge Functions) — separate deployment, untracked `supabase/` |

## What is implemented

Search, metadata and two-phase stream resolution over keyless providers; Media3 background playback
with queue, contextual next/previous and endless radio; mini-player and a two-layout Now Playing with
a live waveform, animated covers and ambient lights; synced, word-timed and karaoke lyrics with
readings and a timing control; manual and automatic equalizer; Smart 8D spatialization and 8D
renders; Hi-Res / best-available / lossless-preferred quality modes; downloads in four tagged formats
with save-to-phone; the local music player (scan + file explorer); favorites, playlists, recents,
imports by URL and file, exports and unlisted share links with QR; the Home feed with daily mixes and
personalised rows; genre hubs and stations; Audio ID recognition with history; three home-screen
widgets; the sandboxed plugin runtime with a store; optional account and local-first sync with
per-device taste; in-app updates; the editorial design language in four languages.

Not implemented, on purpose: Spotify search (an anti-bot token gates it), direct OAuth export into
other services, silent updates, a beta channel, delta updates, an e-mail-code sign-in in the UI
(the data layer has it).

## What is private

The maintainer keeps the spec-driven design record — one spec per slice (`docs/specs/001…024`) and
one ADR per trade-off (`docs/adr/0008…0032`) — outside the published tree, along with the
operational context a coding agent uses on the maintainer's machine. Decisions that matter to a
reader of the code are restated publicly in [`GOVERNANCE.md`](GOVERNANCE.md) § Decision log and in
the log below. The Corresponding Source of the app is complete without the private record.

## Configuration names (values never enter the repository)

`RIZX_SUPABASE_URL` · `RIZX_SUPABASE_PUBLISHABLE_KEY` · `RIZX_GOOGLE_WEB_CLIENT_ID` ·
`RIZX_SHARE_BASE_URL` · `RIZX_TURNSTILE_CHALLENGE_URL` (compiled, not consumed by 1.0.0). Read from
Gradle properties or the environment; absent values compile to empty strings and hide the cloud
screens. The release keystore lives in an uncommitted `Proyecto/keystore.properties`.

## Interfaces other clients depend on

- **The sync wire contract is this app's domain model.** `AlbumRef.source`, `ArtistRef.source` and
  `PlaylistRef.source` are non-null; `ArtistCredit.source` and `Artwork.source` are nullable. A row
  with `"source": null` on a non-null field cannot be decoded and is skipped by this app; the web
  client drops ref-less albums and artists at its serialisation boundary for that reason.
- **Portable playlists:** Rizx JSON v2, XSPF, M3U8 — sanitized, never carrying stream URLs or local
  paths. Rizx JSON is also what the Nuclear-format importer reads.
- **Share links:** `https://<share host>/<token>` and `rizx://share/<token>`; the token is 32 random
  bytes, base64url, unpadded; the server keeps only its hash.
- **Releases:** tag `vX.Y.Z`, a signed `Rizx-X.Y.Z-release.apk` attached, not draft, not pre-release;
  GitHub's per-asset SHA-256 digest is what the updater verifies.
- **Plugins:** Nuclear plugin API v1 as documented in `docs/plugins/`.

## Running log (newest last)

**2026-08-25 — 1.0.0.** Version 1.0.0 (`versionCode 3`) declared once the three home-screen widgets
landed (spec 022 / ADR 0030). Sync moved to a single PostgREST RPC with a Realtime invalidation
channel (ADR 0029). The licence was clarified as GPL-3.0 and the About screen carries the GPL §5(d)
notice; the public documentation set (`ARCHITECTURE`, `FEATURES`, `BUILD`, `PROVIDERS`, `LICENSING`,
`PRIVACY_POLICY`, `THIRD_PARTY_LICENSES`, plugin guides) was refreshed for the public repository.

**2026-09-07 — the wire contract is the phone's model.** Two taste rows written by the web client
with `album.source = null` crashed the app on launch. Every stored track now decodes through
`decodeTrackOrNull` (a bad row costs that row, not the screen); the web client drops ref-less albums
and artists before writing.

**2026-09-08 — editorial Library, Settings and Equalizer** (spec 023 / ADR 0031). The three screens
were rebuilt in the feed design's editorial layout from a shared vocabulary (`ui/components/Editorial.kt`):
framed surfaces with a hard offset shadow, signal-dot eyebrows and serials, playlist collages from a
new digest flow (no migration), the equalizer as a radar with vertical faders and a derived preset.
The owner's defaults for a fresh install landed the same day: crossfade on, Best available quality,
lyrics quality Automatic, animated covers on over any network, Combined home feed.

**2026-09-09 — DM Sans and the web's player stage.** DM Sans replaced Space Grotesk as the display
face app-wide (three optical sizes, chosen by size). Now Playing became the web's phone player: an ink
stage over the paper console with the `■ NOW PLAYING` heading; transport, progress bar and dot
texture untouched by request; the waveform gained per-frame smoothing while playing.

**2026-09-10 — the stage fills the phone; ambient lights; Classic restored.** The stacked player is
weight-based (the cover takes every dp the controls leave, ~95 % of the width in Compact); three
lights in the cover's dominant saturated colours drift behind it from an 18 fps ticker (reduced
motion freezes them). "Every cover went static" was traced to the YouTube canvas source being
switched off by default the day before — YouTube is the fallback most songs rely on — and the default
went back to on. The owner restored the full-bleed Classic stage as its own layout the same day.

**2026-09-13 — section marks, in-app updates, the documentation set.** Each Settings section header
carries its glyph in a red-framed square, as on the web. In-app updates (spec 024 / ADR 0032): a
daily WorkManager check against this repository's GitHub Releases with a 12 h throttle, a
notification once per version, a Settings row and dialog, SHA-256 verification against the asset
digest, hand-off to Android's installer; the repository had no release yet, so the on-device check
was verified as "up to date" and the download → install path by JVM tests. The public documentation
grew a user guide, a technical guide, governance, this context file, a public `AGENTS.md`,
contributing/security/conduct files, issue and pull-request templates, a CI workflow and the README
with the banner and screenshots. `docs/adr/` was found deleted from disk during the day and restored
from the Recycle Bin.
