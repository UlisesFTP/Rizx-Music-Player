# Rizx Player — project governance

_Written 2026-09-13. This document says how the project is run: who decides what, how a change
travels from an idea to a commit, what "done" means, which rules outrank convenience and where each
is enforced, how a release is cut, where every kind of knowledge is recorded, and what an agent —
human or LLM — may and may not do. It is the rulebook; [`TECHNICAL_GUIDE.md`](TECHNICAL_GUIDE.md) is
the map of the code and [`USER_GUIDE.md`](USER_GUIDE.md) is the product seen from the listener's side.
When this file and a spec disagree, the spec wins for its own scope and this file must be corrected._

## 1. Roles and sources of truth

| Role | Who | Decides |
|---|---|---|
| Maintainer (owner) | `UlisesFTP`, communicates in Spanish | Product scope and priorities, every deviation from a spec, what is committed, tagged, released and published, every backend and dashboard setting, the values of every configuration variable, the licence, and whether an agent may cross a rule. |
| Contributors | Anyone opening an issue or a pull request | How to implement a slice inside the rules below; they propose, measure and record, and stop for anything the maintainer must decide. |
| Coding agents | Claude Code, Codex, OpenCode, Copilot and the like, driven by a person | The same as a contributor, plus the boundaries of §11. The person driving the agent is the author of what it produces. |
| Rizx Web | The sibling browser/desktop project | Nothing here. It is the reference for the shared editorial design and a consumer of the sync wire contract; this app is the reference for **behaviour** (queue, sync, formats, matching). |

When sources disagree, the order is:

1. the current code and its tests;
2. the active spec of the area (the maintainer's private record; its decisions are restated in §9 and
   in [`CONTEXT.md`](CONTEXT.md));
3. the public documents in `docs/`, which must then be corrected.

The language rule is part of governance: the maintainer is addressed in Spanish; source code,
comments, commit bodies and every file under `docs/` stay in English. Commit subject lines have been
written in Spanish since the first push; either language is accepted from contributors.

## 2. The workflow: Spec → Plan → Implement → Test → Report

Every change is a **slice**: one request, one scoped piece of work, finished and recorded before the
next begins. The five steps each leave a trace the next person or agent relies on.

| Step | What it produces | Where it lives |
|---|---|---|
| Spec | For a new behaviour, a changed contract (model, screen, rule) or a trade-off: a short written statement — *why*, *contract*, *invariants kept*, *verification*. A pure bug fix needs no spec; its log entry is the record. | The maintainer's private spec/ADR record; for a contributor, the issue. |
| Plan | Specs read, relevant files, the implementation slice, files expected to change, tests to add, risks and assumptions — in the shape `AGENTS.md` prescribes. | The session or the pull-request description. |
| Implement | Only what the slice allows, inside the invariants of §4 and the design rules of §5. | `Proyecto/app/src/main`, `res/`, `docs/` |
| Test | JVM tests for every rule introduced; an instrumented migration test when Room's schema moves; a device or emulator run when the claim is about a device; the gate. | `Proyecto/app/src/test`, `src/androidTest`, the numbers in the report |
| Report | To the maintainer, in Spanish: what was found, what changed, what was verified and how, what was left out. To the repository, in English: an entry at the tail of `CONTEXT.md`, the document that describes the changed screen or contract, the decision log when a decision was made. | `CONTEXT.md`, `USER_GUIDE.md`, `TECHNICAL_GUIDE.md`, this file |

Reports state outcomes as they happened: a red gate is reported red with its output, a skipped step
is reported skipped, a device result is claimed only when a device actually ran.

## 3. Definition of done

A slice is done when **all** of the following hold; otherwise the report says which one does not.

- [ ] The change is inside the slice that was asked for — no quiet widening, narrowing or "while I
      was there" refactors.
- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug` passes from `Proyecto/`; the pull-request
      CI shows the same.
- [ ] Every new rule has a JVM test that would fail without it; a Room version bump ships its
      migration, its exported schema JSON and its `RizxMigrationTest` case in the same change.
- [ ] A claim about a device, an emulator or a real provider was actually executed, and the report
      says on what.
- [ ] No invariant of §4 is bent without a spec that says so and the maintainer's decision recorded.
- [ ] Every user-visible string exists in `values`, `values-es`, `values-pt` and `values-fr`.
- [ ] A new dependency is justified in the report and listed in `THIRD_PARTY_LICENSES.md` **and**
      `ui/screens/LicenseData.kt`.
- [ ] The documents that describe what changed are updated (`USER_GUIDE.md` for a screen or setting,
      `TECHNICAL_GUIDE.md` for a module or flow, this file for a rule) and a dated entry is appended to
      `CONTEXT.md`.
- [ ] No value of any configuration variable, token, key, keystore or stream URL appears in code,
      tests, docs, logs, screenshots or commit messages.

## 4. Architecture invariants (the rules that outrank convenience)

Restated from `AGENTS.md` with where each is enforced, so a reader can check rather than trust.

| Invariant | Why | Where it is enforced or pinned |
|---|---|---|
| `domain/` imports nothing from Android, Media3, Retrofit, Room or a provider DTO. | The domain is the part testable without a device and portable as the web's wire format. | Package layout; review; the JVM suite runs the fingerprint, the DSP, the sync engine and the matchers without Android. |
| `ProviderRef(provider, id)` is the canonical identity; `url` is excluded from equality; a `Track` has no id of its own. | The same song from two catalogues must be two things; a URL is a dead link tomorrow. | `domain/model/ProviderRef.kt` (a plain class, not a data class); tests under `src/test/…/domain/model`. |
| Metadata and streaming providers are separate contracts; resolution is `Track → StreamCandidate list → just-in-time URL`. | Search one catalogue, play from another; a catalogue row outlives any stream. | `domain/provider/{MetadataProvider,StreamingProvider}.kt`; `domain/usecase/StreamingResolver.kt` and its tests; `data/repository/StreamingRepositoryImpl.kt`. |
| Stream URLs are ephemeral: never in Room, the session snapshot, exports, sync documents or a URL-keyed cache. | A stored URL is a dead link or an IP-bound secret. | `Track.stripResolutionState()`, `data/local/store/TrackJson.kt`, `PortablePlaylist`; the audio cache keyed by `identityKey#codec`; tests under `data/local/store` and `data/sync`. |
| `PlaybackService` owns the single audio ExoPlayer; Composables never create, hold or call a player; the UI reaches playback through `PlaybackController`. | Media session, effects, timing and restore all assume one source of truth. | `playback/service/PlaybackService.kt`; `playback/MediaControllerPlaybackController.kt` bound in `PlaybackModule`; review. The muted canvas player is the one documented exception (ADR 0017). |
| The UI never calls a provider, a repository implementation or a network client. | One boundary to test; one place where errors become safe copy (`Throwable.toSafeMessage`). | ViewModels are the only importers of repositories; `core/error/AppError.kt`; review. |
| `QueueItem.id` and `PlaylistItem.id` are insertion identities distinct from the track's. | The same song may sit twice; reorder and remove must keep the cursor valid. | `domain/model/Queue.kt`, `data/repository/InMemoryQueueRepository.kt` and its tests. |
| Providers, plugins and cloud calls fail independently; a broken one degrades alone. | The app must work with any source down, no plugin and no backend. | `DashboardRepositoryImpl` (per-section isolation), `StreamingRepositoryImpl` (fallback chain), `JsProviderInvoker` + quarantine, `NoSyncCoordinator` when unconfigured; tests under `data/repository`, `data/plugin`, `data/sync`. |
| Sources are keyless: public endpoints and tokens a page hands to any visitor; never a defeated access control. | Legal and ethical line; also what keeps the app buildable with no configuration. | ADR 0018 (Spotify search absent); `PROVIDERS.md`; review. |
| A stored row this build cannot decode is skipped, never thrown from a Flow. | Two web-written rows crashed the app on launch on 2026-09-07. | `TrackJson.decodeTrackOrNull`; `RecentlyPlayedRepositoryTest`. |
| Room is the library's source of truth; sync is local-first and optional; the app is complete without an account. | Offline must be identical; signing out keeps everything. | `data/sync/SyncRunner.kt` (outbox, echo filtering, recovery snapshots); `ARCHITECTURE.md` § sync; tests under `data/sync`. |
| Nothing audible depends on the audio buffer size; effects wrap the sink, they are not `AudioProcessor`s. | Media3 promises no buffer size and drops custom processors on the float path. | `playback/spatial/SmartSpatialEngine.kt` (stepped per fixed segment), `PcmTappingAudioSink`; JVM tests under `playback/spatial`. |
| Configuration is by property **name** only; no secret ever enters the repository. | The APK must contain nothing that grants more than an anonymous client has. | `app/build.gradle.kts` (`publicConfig`), `.gitignore`, `BUILD.md`, review. |
| No dependency without a stated reason, and none that duplicates another; every one lands in both licence lists. | Size, licences, and the fact that each dependency is a maintenance promise. | `THIRD_PARTY_LICENSES.md`, `ui/screens/LicenseData.kt`, review. |
| Every user-visible string exists in the four locales; `Build.VERSION` checks live in Android-only classes. | The whole UI ships in four languages; the JVM-tested pipeline never branches on SDK. | `res/values{,-es,-pt,-fr}`; review. |

## 5. Design governance

- The visual language is the **editorial / brutalist** one shared with Rizx Web: kickers and display
  titles, 2dp ink frames with a hard offset shadow, red signal dots and serials, dot-matrix
  numerals, mono labels, blueprint backgrounds. It is built from one vocabulary,
  `ui/components/Editorial.kt` (plus `Common.kt`, `Decor.kt`, `Tech.kt`), and new screens are built
  from that vocabulary rather than from ad-hoc styling.
- **Tokens, not hex.** Colours come from `RizxTheme.colors`; both themes — *Paper* (light) and *Ivory*
  (dark) — are checked for every change, and the dark shadow is red where a black one would vanish.
- **Type.** DM Sans for display (`sg()`), Martian Mono for labels and body (`mr()`, `code()`), Doto
  for numerals (`dot()`). No other face is added without a licence entry and a reason.
- **Behaviour follows the phone, pictures may follow the web.** When the web's design and the
  phone's behaviour disagree, the web supplies the picture and the phone the behaviour; a deviation
  from the web's look is named in the report.
- **Accessibility floors are not negotiable:** 48dp targets, one named semantic node per toggle row
  (pinned by `SettingsToggleSemanticsTest`), content descriptions on every icon button, reduced motion
  honoured (the ambient lights freeze when animator scale is 0), no information by colour alone.
- **Motion serves the music.** Continuous animation is allowed where sound moves (waveform, lights,
  karaoke) and is driven by a `delay` ticker or the audio, never by a per-frame full-screen canvas.
- Agents do not design unasked: a missing screen is asked for, not improvised.

## 6. Branches, commits, releases

- **One line: `main`.** There is no development branch; a slice lands on `main` when the maintainer
  commits it. Rizx Web is a separate repository with its own `main` and `electron` lines.
- **Commits happen only when the maintainer asks** — this binds agents working on the maintainer's
  machine; a contributor's fork is their own. History is never rewritten; a fix is a new commit.
  Attribution trailers requested by tooling are kept.
- **One slice per commit** where practical; the subject says what changed for a listener or a
  developer; the `CONTEXT.md` entry carries the detail.
- **CI** (`.github/workflows/android-ci.yml`) runs the gate on every push to `main` and every pull
  request. It is a safety net; the gate is run locally first and reported.
- **A release is:** bump `versionName` and `versionCode` → gate (plus migration test if the schema
  moved) → refresh `README.md`'s snapshot in `docs/`, `BUILD.md`, the policy dates and both licence
  lists → `assembleRelease` with the real keystore → `apksigner verify` → `git tag vX.Y.Z` → a GitHub
  release for the tag, not draft, not pre-release, with `Rizx-X.Y.Z-release.apk` attached and the
  notes in the body. Installed apps pick it up within a day (spec 024).
- **Signature continuity** is the one thing that cannot be recovered: the keystore and both
  passwords are backed up outside the repository, and `assembleRelease` refuses to package without it.
- **What is never published:** `docs/specs/`, `docs/adr/`, `docs/checklists/`, the agent context,
  `supabase/`, `assets/plugins/`, `local.properties`, `keystore.properties`, keys of any kind
  (`.gitignore` is the authoritative list).

## 7. Working on the maintainer's machine

The public rules for anyone whose agent runs where the maintainer works; the private detail is in
the maintainer's own tool configuration.

- Never commit, stage, push, tag, publish or `git clean` unless asked in the current session; never
  discard or overwrite a file you did not write.
- Never create or edit tool configuration, hooks or permissions without the exact contents approved.
- Never sign in with anyone's credentials; never write to a live library or cloud project.
- Never print, quote or copy a configuration value, a token or a stream URL.
- Use emulators and isolated instances for anything that must be observed; say what ran.

## 8. Where knowledge is recorded

| Kind of knowledge | File | Convention |
|---|---|---|
| Standing instructions for anyone touching code | `AGENTS.md` | Bootstrap order, workflow shapes, invariants, commands, boundaries. Kept short. |
| Where the project stands, and the running log | `docs/CONTEXT.md` | Header = state; tail = dated entries, **newest last**. |
| The rulebook | `docs/GOVERNANCE.md` | This file. Updated when a rule changes; the decision log is appended, never rewritten. |
| The map of the code | `docs/TECHNICAL_GUIDE.md` | Layers, packages, flows, storage, network, build, tests, release. |
| The product for listeners | `docs/USER_GUIDE.md` | Every screen, control, setting, limitation and troubleshooting step. |
| Deep dives | `docs/ARCHITECTURE.md`, `docs/PROVIDERS.md`, `docs/FEATURES.md`, `docs/BUILD.md`, `docs/plugins/` | Single-topic references linked from the guides. |
| Policies | `docs/PRIVACY_POLICY.md`, `docs/LICENSING.md`, `docs/THIRD_PARTY_LICENSES.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md` | Dated; refreshed at each release. |
| Contribution path | `CONTRIBUTING.md`, `.github/` | Setup, the gate, templates. |
| The private design record | the maintainer's `docs/specs/`, `docs/adr/`, agent context | One spec per slice, one ADR per trade-off; git-ignored; decisions restated here. |
| Agent-private memory | outside the repository | Pointers and traps only; anything durable is written to `docs/`. |

## 9. Decision log

Durable decisions, oldest first, with where the reasoning is recorded. Add a line; never rewrite one
— a reversed decision gets a new line that points back.

| Date | Decision | Record |
|---|---|---|
| 2026-06-29 | Domain shapes follow the upstream model; `ProviderRef` equality excludes `url`; a `Track` has no id of its own. | ADR 0008 |
| 2026-07-13 | iTunes Search as the first real provider, Audius as full-length streaming, Deezer as the keyless metadata and detail provider; dashboards and playlist importers are multi-active fan-out. | ADR 0009–0012 |
| 2026-07-13 | A hybrid plugin system — native YouTube audio through NewPipeExtractor plus a sandboxed JavaScript runtime — replaces "no JS runtime". | ADR 0014 (supersedes 0013) |
| 2026-07-16 | Downloads store the bytes as delivered, indexed by track identity; local files resolve before any network request. | ADR 0015 |
| 2026-07-17 | The repository is published on GitHub as its own project. | first commit |
| 2026-07-28 | The karaoke view runs on a per-frame clock, not a poll; an item is resolved by the catalogue it came from (owner-first). | ADR 0016, 0020 |
| 2026-07-30 | An automatic equalizer with a curve per song; a listening log and the recommendations built on it; the artist page with a validated Wikipedia biography. | ADR 0021–0023 |
| 2026-07-31 | Optional community-lossless audio with the index as a plugin and no plugin archive in the repository; a data saver that actually saves. | ADR 0024, 0025 |
| 2026-08-10 | Third-party plugins are isolated from each other, declare an API version and get an honest security story. | ADR 0026 |
| 2026-08-12 | Apple Music and SoundCloud join as catalogues; Spotify **search** is deliberately absent because its gate is an access control — "keyless means public, not merely reachable". | ADR 0018 |
| 2026-08-14 | Plugin runtime v2: one invoker with timeouts and quarantine; manual EQ presets follow the device's real frequencies. | ADR 0019, 0027 |
| 2026-08-17 | Animated covers as a provider chain behind a network policy, on a second muted player; Supabase for optional local-first sync and unlisted sharing. | ADR 0017, 0028 |
| 2026-08-25 | Sync is one PostgREST RPC per run with Realtime as invalidation only; three home-screen widgets on RemoteViews and the media session; version 1.0.0; GPL-3.0 (from AGPL) with no upstream-attribution wording. | ADR 0029, 0030; `LICENSING.md` |
| 2026-09-07 | The sync wire contract is this app's model; stored rows decode leniently and never throw from a Flow. | `CONTEXT.md` |
| 2026-09-08 | Library, Settings and Equalizer take the web's editorial layout; the owner's defaults apply to a fresh install. | ADR 0031, spec 023 |
| 2026-09-09 | DM Sans is bundled as the display face; Now Playing becomes the web's stage over the app's console. | ADR 0031 (amended) |
| 2026-09-10 | The stage is weight-based with ambient lights from the cover palette; the YouTube canvas source stays on by default (turning it off made covers static); Classic keeps the full-bleed stage. | `CONTEXT.md` |
| 2026-09-13 | In-app updates read GitHub Releases and hand off to the system installer; AGENTS.md is public; the documentation set (user guide, technical guide, governance, context) is part of the definition of done; CI runs the gate on pull requests. | ADR 0032, spec 024, this file |
| 2026-09-13 | Ignore rules that name a folder are anchored to the root (`/supabase/`); the first CI run showed an unanchored rule had kept the app's `data/remote/supabase` package out of the public tree. A release tag is cut only after CI is green on that commit, because CI — not the maintainer's tree — is what proves the published source builds. | `CONTEXT.md` 2026-09-13, `.gitignore` |

## 10. Open items and known debt

Recorded so nobody rediscovers them; none blocks daily work.

- The first real GitHub release is also the first end-to-end run of the updater's download → verify
  → install path (JVM-tested until then).
- The e-mail-code sign-in exists in the data layer but is not offered by the UI.
- `quickjs-kt` cannot interrupt a non-allocating infinite loop; restart abandons the thread.
- `jaudiotagger` stays an Android-compatibility risk to re-evaluate.
- "Normalize volume" is a fixed gain, not LUFS; crossfade is a volume envelope, not two players.
- Room migrations 1–3 predate the exported schemas and are not provable by the instrumented suite.
- The shared HTTP User-Agent is a constant; bump it with the major version.
- The share-link host must serve `/.well-known/assetlinks.json`; `share-site/` documents it but
  does not ship one (it carries the deployment's certificate fingerprint).
- Until the fix of 2026-09-13 is pushed, `origin/main` lacks `data/remote/supabase` and does not build from a clone; the tag `v1.0.0` must be cut after that push.
- The tracked `.vite/deps/` metadata at the repository root is a leftover with no role in the Android
  build; removing it is a housekeeping commit for the maintainer.
- Older docs (`ARCHITECTURE.md`, `FEATURES.md`, `PROVIDERS.md`) were refreshed on 2026-09-13 but
  keep their 2026-08-25 structure; the guides are the entry points.

## 11. Protocol for LLM agents

The repository is designed to be worked by agents without becoming a black box. An agent session
follows this order, every time:

1. **Read** `AGENTS.md`, then `docs/CONTEXT.md` (header, then the tail, newest last), then this
   file, then `docs/TECHNICAL_GUIDE.md` for the map and the deep dive of the area in hand.
2. **Confirm the state** (`git status`, `git log --oneline -3`) and that the request is one slice.
3. **Plan before code** (§2), in the shape `AGENTS.md` gives. Ask the maintainer only for decisions
   that are theirs; make routine judgement calls and state them.
4. **Implement inside the invariants** (§4) and the design rules (§5). Never edit files the change
   does not require; never touch configuration, credentials or another project.
5. **Verify** with the gate and, when the claim needs one, a device or emulator; say what ran.
6. **Report red as red.**
7. **Record** (§2, §8): the `CONTEXT.md` entry, the guide that describes the change, the decision
   log when a decision was made.
8. **Commit, tag, release only when asked** (§6).
9. **Reply to the maintainer in Spanish**, leading with the outcome; keep code and file names out of
   the prose except where the reader must go there.
10. **Never** print a configuration value, never claim what was not observed, never reformat the
    repository.

An agent that finds this document wrong fixes it in the same slice and says so in the report.
