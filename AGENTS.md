# AGENTS.md — standing instructions for anyone touching the code

Rizx Player is a native Android music player (Kotlin, Jetpack Compose, Hilt, Media3, Room). This
file is the short, always-current set of instructions for a **human contributor or a coding agent**
(Claude Code, Codex, OpenCode, Copilot, …) working in this repository. It is deliberately brief;
everything it points at is longer.

The project is presented as its own work under GPL-3.0. Do not add "derived work", "fork" or
upstream-attribution wording to `LICENSE`, `NOTICE`, the README, `docs/` or the About screen.
Naming the Nuclear *plugin API* and *playlist file format* is fine — those are interoperability facts.

## Required bootstrap

Read, in this order, before changing code:

1. this file;
2. [`docs/CONTEXT.md`](docs/CONTEXT.md) — the state header, then the **tail** of the running log
   (newest last), so you know what just changed and why;
3. [`docs/GOVERNANCE.md`](docs/GOVERNANCE.md) — the workflow, the definition of done, the invariants
   with where each is enforced, the decision log;
4. [`docs/TECHNICAL_GUIDE.md`](docs/TECHNICAL_GUIDE.md) — the map of the code, then the deep dive
   for the area in hand (`ARCHITECTURE.md`, `PROVIDERS.md`, `BUILD.md`, `plugins/`);
5. [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) when the change touches a screen — it states what a
   listener sees, and it must still be true after your change.

The maintainer also keeps a private spec/ADR record outside the published tree; a public decision
that matters to the code is restated in `docs/GOVERNANCE.md` § Decision log and `docs/CONTEXT.md`.
`docs/README.md` indexes everything.

## The workflow

```
Spec → Plan → Implement → Test → Report
```

Every change is a **slice**: one scoped piece of work, finished and recorded before the next.

- Do not start by writing code. Inspect the repository and the documents above first.
- **Before changing files**, state your plan in this shape:

  ```md
  ## Plan
  ### Specs read
  ### Relevant existing files
  ### Implementation slice
  ### Files expected to change
  ### Tests to add/run
  ### Risks / assumptions
  ```

- **After changing files**, report in this shape:

  ```md
  ## Summary
  ## Files changed
  ## Tests
  - Command: `...`
  - Result: ...
  ## Acceptance criteria status
  ## Known gaps
  ```

- Report red as red. Never claim a device or emulator result that was not executed. If tests were
  not run, say why.
- Implement only the requested slice. No unrelated refactors, no quiet widening or narrowing.
- A slice that changes a screen, a setting or a contract also updates the document that describes
  it (`docs/USER_GUIDE.md`, `docs/TECHNICAL_GUIDE.md`, `docs/GOVERNANCE.md`) and adds a dated entry
  to the tail of `docs/CONTEXT.md`. That is part of "done".

## Hard architecture rules

These outrank convenience. Where each is enforced: `docs/GOVERNANCE.md` §4.

```text
- domain/ stays pure Kotlin: no Android, Media3, Retrofit, Room or DTO imports.
- ProviderRef(provider, id) is the canonical identity of upstream content. Never a title, name or URL.
- Metadata providers and streaming providers stay separate contracts.
- Stream resolution is two-phase (Track → StreamCandidate list → just-in-time URL); URLs are
  ephemeral and are never persisted in playlists, favorites, caches keyed by URL, or sync documents.
- PlaybackService (MediaSessionService) owns the single audio ExoPlayer. Composables never create,
  hold or call a player; the UI talks to PlaybackController / ViewModels only.
- Canvas may own a separate, muted, video-only player. Nothing else may.
- Compose never calls a provider, a repository implementation or a network client directly.
- QueueItem.id and PlaylistItem.id are insertion identities, distinct from Track identity.
- Providers, plugins and cloud calls fail independently; a broken one degrades alone and never
  crashes the app.
- Sources are keyless: public endpoints and tokens a page hands to any visitor are fine; defeating an
  access control is out of scope however easy.
- Room is the source of truth for the library; sync is local-first and optional; the app is complete
  without an account and without a backend.
- No secret, key, credential, signing material or configuration value ever enters the repository, a
  test, a log, a document or a commit message. Configuration is by property NAME only.
- No new dependency without a stated reason, and none that duplicates an existing one; a new
  dependency lands in docs/THIRD_PARTY_LICENSES.md and ui/screens/LicenseData.kt in the same change.
- Every user-visible string exists in values, values-es, values-pt and values-fr.
- Build.VERSION checks live in Android-only classes; the JVM-tested pipeline never branches on SDK.
```

## Core domain rules

```text
ProviderRef(provider, id, url?) — identity is provider + id; url is excluded from equality.
Track has no id: its identity is Track.source. Artists are ArtistCredits, album is an AlbumRef,
artwork is an ArtworkSet, durations are milliseconds, timestamps ISO-8601. streamCandidates is
transient; strip it before persisting (TrackJson).
Queue = List<QueueItem> + currentIndex + repeat/shuffle + QueueContext. Reorder keeps the current
item; remove keeps currentIndex valid; shuffle stores the un-shuffled order.
Favorites, playlists and recents are keyed by ProviderRef; the same track may sit twice in a queue
or a playlist because their items carry their own ids.
A stored row this build cannot decode is skipped, never thrown from a Flow (decodeTrackOrNull).
```

## Commands and the gate

Run everything from `Proyecto/` with JDK 21 (17–23 work; Java 25 does not).

| What | Command |
|---|---|
| The gate — unit tests, lint, debug build | `./gradlew testDebugUnitTest lintDebug assembleDebug` |
| One test class | `./gradlew testDebugUnitTest --tests "fm.rizx.player.data.provider.ProviderRegistryTest"` |
| Instrumented tests (device/emulator; required for Room migrations) | `./gradlew connectedDebugAndroidTest` |
| Minified smoke build, debug-signed | `./gradlew assembleReleaseTest` |
| Distributable build (needs an uncommitted keystore) | `./gradlew assembleRelease` |

Run the gate before reporting a slice complete. CI (`.github/workflows/android-ci.yml`) runs the
same gate on every pull request; it is a safety net, not a substitute.

## Boundaries

- **Commit, push, tag or publish only when the maintainer asks in the current session.** Never
  rewrite history, never `git clean`, never discard or overwrite files you did not write.
- Do not create or edit tool configuration (`.claude/settings*.json`, `.mcp.json`, `opencode.json`,
  hooks, credentials) unless the maintainer approves the exact contents.
- Do not sign in with anyone's credentials, and do not modify anyone's live library or cloud data.
- Do not add a backend dependency or a paid service. The app must keep working with no account and
  no backend.
- Reply to the maintainer in their language (Spanish); code, comments and every file in `docs/`
  stay in English.

## Where things are

| Area | Path |
|---|---|
| Models, contracts, use cases (pure Kotlin) | `Proyecto/app/src/main/java/fm/rizx/player/domain/` |
| Providers, remote clients, stores, repositories, downloads, sync, updates, plugins | `…/data/` |
| Playback service, stream resolver, audio effects, spatial DSP, caches | `…/playback/` |
| Screens, ViewModels, design system, navigation | `…/ui/` |
| Home-screen widgets | `…/widget/` |
| DI modules, network stack, errors | `…/core/` |
| JVM tests (mirror the package tree; shared fakes at the root) | `Proyecto/app/src/test/` |
| Instrumented tests (Room migrations, karaoke timing, semantics) | `Proyecto/app/src/androidTest/` |
| Exported Room schemas (one per version, committed) | `Proyecto/app/schemas/` |
| Strings, four locales | `Proyecto/app/src/main/res/values{,-es,-pt,-fr}/` |
| Documentation | `docs/` (index in `docs/README.md`) |

## Review checklist

```text
- [ ] The slice matches what was asked; nothing unrelated changed.
- [ ] domain/ still has no Android imports; the UI touches no provider or player.
- [ ] Identity is ProviderRef; no stream URL is persisted; queue/playlist item ids are their own.
- [ ] Failures of providers, plugins and cloud calls are isolated.
- [ ] New rules have JVM tests; a schema change ships migration + schema JSON + migration test.
- [ ] Strings exist in all four locales.
- [ ] Dependencies justified and listed in both licence lists.
- [ ] The gate ran; the report says what passed, what failed and what was not run.
- [ ] USER_GUIDE / TECHNICAL_GUIDE / GOVERNANCE updated where a screen, setting or contract moved;
      a dated entry appended to docs/CONTEXT.md.
- [ ] No secret or configuration value anywhere.
```
