# Rizx Player — project context

_State header verified against the working tree on 2026-10-31. The running log at the end is
append-only, newest last._

This is the public "where the project stands" document: the current state in one table, what is
implemented, what is optional, what is private, and a dated log of what changed and why. A
developer or coding agent reads the header, then the **tail** of the log, before touching code
([`../AGENTS.md`](../AGENTS.md) § Required bootstrap). The rules are in [`GOVERNANCE.md`](GOVERNANCE.md);
the map of the code is [`TECHNICAL_GUIDE.md`](TECHNICAL_GUIDE.md).

## State

| Area | Current state |
|---|---|
| App | Rizx Player **1.0.2** (`versionCode 5`), package `fm.rizx.player`, GPL-3.0 |
| Android | Kotlin 2.0.21 · Jetpack Compose (BOM 2024.12.01) · Hilt 2.52 · Media3 1.5.1 · `minSdk 26` · `targetSdk/compileSdk 36` |
| Build | Gradle 8.12 · AGP 8.9.1 · KSP 2.0.21-1.0.28 · JDK 21 to run Gradle (17–23 work, 25 does not) |
| Persistence | Room schema **7** (exported schemas 4–7) · four Preferences DataStores · JSON stores under `filesDir` |
| Size | 496 main Kotlin files · 202 JVM test files · 6 instrumented test files · 4 locales (en, es, pt, fr) |
| Verification | **1,702 JVM tests in 193 suites**, `lintDebug` 0 errors, `assembleDebug` pass, 21 instrumented tests on `rizx28` (2026-10-31); `assembleRelease` last verified 2026-09-13 |
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
`RIZX_SHARE_BASE_URL` · `RIZX_TURNSTILE_CHALLENGE_URL` (compiled, not consumed by the app). Read from
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

**2026-09-13 — the public tree was missing a package; the first CI run caught it.** The first push
of the Android CI workflow failed in `kspDebugKotlin`: Hilt could not resolve `SessionStore`,
`SupabaseAuthApi` and their siblings. Cause: the `.gitignore` rule `supabase/`, written for the
backend folder at the repository root, was unanchored and also matched the app's own
`data/remote/supabase` Kotlin package (six files) and its test, so they had never been committed —
every clone since the first push built only on the maintainer's machine. Fix: the rule is now
`/supabase/`, the package and its test are tracked, and a checkout of the index (no
`local.properties`, no bundled plugin, no keystore — CI's conditions) passes the full gate: 1,695
tests in 193 suites, lint 0 errors, `assembleDebug`. Lesson recorded in `GOVERNANCE.md` §9: the CI
gate is what proves the *published* source builds, not the maintainer's working tree.
The second CI run then failed one test, `HomeViewModelTest › network failure yields Offline`, with
`UncaughtExceptionsBeforeTest`: the ViewModel's cache read (`withContext(Dispatchers.IO)`) and taste
flows (`flowOn(Dispatchers.Default)`) ran on real threads, and on the slower runner a coroutine from
the previous test resumed onto a `Dispatchers.Main` the test rule had already torn down. Fix: the same
seam `SearchViewModel` already had — overridable `io`/`background` dispatchers (`useDispatchers`, a
`@VisibleForTesting internal` method, since Hilt has no `CoroutineDispatcher` binding) and the three
derived `StateFlow`s built lazily so they read the overridden dispatcher; every construction in the
test class hands over the test scheduler. No other ViewModel with JVM tests touches a real dispatcher.

**2026-09-13 — v1.0.0 published.** After CI went green on commit `592e1d2` (the gitignore fix and
the HomeViewModel dispatcher seam), the tag `v1.0.0` was pushed and the first GitHub release was
created with `Rizx-1.0.0-release.apk` (11,867,389 bytes, built from that commit, SHA-256
`11a99060…e9906` as GitHub's asset digest confirms) and the release notes; GitHub attached the source
archives of the tag. `releases/latest` now answers, so installed apps report "up to date" and the
README badges resolve. The next release bumps `versionName`/`versionCode` before tagging.

**2026-10-30 — large reads are transactions; a crash on opening the app after using the web client.**
The maintainer's phone (1.0.0) closed with `IllegalStateException: Couldn't read row 446, col 0 from
CursorWindow` when the app was opened after listening on the desktop client, and did not close on the
next launch. Retraced with the release `mapping.txt`, the frame is `TasteContributionDao.observeAll`,
collected by Home. Android caches a cursor's row count on its first window and re-runs the query for
each later one; outside a transaction a write between two windows can leave the cursor past the end.
Sync upserts a row and then prunes that device to 300, so a read that counted between the two steps
overruns by one. The second launch survives because those rows are already applied, so nothing is
pruned. The row number in the message counts from the window's start, not the table's.
The fix is `@Transaction` on the list reads that can outgrow a window: taste contributions, favorites,
recently played and playlist items, with no schema change. `LargeResultConsistencyTest` runs a real WAL
database with a writer against a reader; on `rizx28` both of its tests failed before the fix with the
phone's stack and passed after it. The gate passes (1,695 JVM tests in 193 suites, lint 0 errors,
`assembleDebug`) and so does the instrumented suite (21 tests).
The real sequence ran once on an API 35 Play Store emulator with the minified `releaseTest` build and the
maintainer's account. The maintainer played new songs on the desktop client, the app was then opened on
Home, and the sync pulled them (3 favorites, 6 taste rows, nothing sent) with no crash; the sign-in sync
before it applied 3,828 taste rows, also with no crash. One clean run does not prove the fix on its own,
because the failure depends on timing and the old build was not run on this path; the instrumented test
is the proof. The fix ships in 1.0.1; 1.0.0 still carries the bug.

**2026-10-30 — v1.0.1 published.** Version 1.0.1 (`versionCode 4`) carries the fix above and nothing else.
The first CI run on commit `0a281fb` failed after 37 seconds, before compiling anything, because the runner
could not resolve the Hilt Gradle plugin; the plugin line had not changed, and a rerun of the same commit
passed. The tag `v1.0.1` was then pushed on that commit and the release was created with
`Rizx-1.0.1-release.apk` (11,867,389 bytes, built from that commit, SHA-256 `8923d344…3f3a8a`, as GitHub's
asset digest confirms; the size matching 1.0.0's is a coincidence, the code differs). `apksigner` confirms
the published signing certificate. `releases/latest` now answers `v1.0.1`. The updater's path from 1.0.0 to
1.0.1 on a real device had not been observed when this entry was written.

**2026-10-31 — the update dialog opens by itself; the updater works end to end** (spec 025). A listener who
missed the notification and never opened Settings could not learn about a release. The update dialog now
lives in `RizxApp` and opens by itself once per version, while the app is on screen; the Settings row and the
notification tap open that same dialog. A version already prompted posts no notification, and opening the
prompt takes down one posted just before it. The lookup window went from 12 hours to 1, and the check also
runs on return to the foreground. The download still starts only when the listener asks. No string or schema
changed. Long release notes had pushed the dialog's buttons out of sight, so only its content scrolls now.

Verified on an API 35 Play Store emulator with a release-signed build of this code posing as 1.0.0, against
the real v1.0.1 on GitHub. The dialog opened by itself with no notification; Later held across a restart; the
Settings row and the notification's intent opened the same dialog without leaving Home; a version found with
the app in the background posted the notification, kept the prompt for the return, and the prompt took the
notification down. Download, verification, the install permission and Android's installer then worked, and
the installed APK's SHA-256 equals the published `8923d344…3f3a8a`: the first observed end-to-end update since
spec 024. Two things seen on the way: Google Play Protect showed "App scan recommended" on the first install
attempt, which the emulator lost connection during, so answering it was not observed, and it did not appear on
the retry; and tapping Install after a failed attempt brings back Android's old failure screen until Done is
tapped. The gate passes (1,702 JVM tests in 193 suites, lint 0 errors, `assembleDebug`) and so does the
instrumented suite on `rizx28` (21 tests). A phone sees this from 1.0.2, the release that carries it.

**2026-10-31 — v1.0.2 published.** Version 1.0.2 (`versionCode 5`) carries the update prompt above. CI passed
on commit `8c67e5f` at the first attempt; the tag `v1.0.2` is on that commit and the release has
`Rizx-1.0.2-release.apk` (SHA-256 `7b554e1c…124ea1`, as GitHub's asset digest confirms). `apksigner` confirms
the published signing certificate. Its size, 11,867,389 bytes, is the same as 1.0.0's and 1.0.1's, which the
2026-10-30 entry called a coincidence; three in a row is not. The likely reason is that signing pads the file
to a 4 KiB boundary, so small code changes do not move the total. Tell builds apart by SHA-256; `classes.dex`
does differ. Phones on 1.0.0 and 1.0.1 hear about this release through the notification and the Settings row;
the prompt itself first shows for the release after this one, on phones already running 1.0.2.
