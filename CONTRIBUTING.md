# Contributing to Rizx Player

Thank you for considering a contribution. Rizx is a native Android music player with a small
maintainer team, a spec-driven way of working and a handful of architectural rules that keep it
honest. This page tells you how to get from an idea to a merged change. The rulebook behind it is
[`docs/GOVERNANCE.md`](docs/GOVERNANCE.md); the map of the code is
[`docs/TECHNICAL_GUIDE.md`](docs/TECHNICAL_GUIDE.md).

## Before you start

1. Read [`AGENTS.md`](AGENTS.md). It is short, and it is written for humans as much as for coding
   agents: the invariants, the commands, what to read, what never to do.
2. Skim [`docs/CONTEXT.md`](docs/CONTEXT.md) for where the project stands and the decision log, so you
   do not propose something already decided against.
3. Open an issue (or pick one) and say what you intend to change **before** writing a large patch.
   Small fixes — a typo, a crash with a clear cause, a missing translation — can go straight to a pull
   request.

## Setting up

| Need | Version |
|---|---|
| JDK for Gradle | **21** (17–23 work; Java 25 does not) |
| Android SDK | `compileSdk 36` installed; the app runs on API 26+ |
| IDE | Any Android Studio compatible with AGP 8.9.1 |

```bash
git clone https://github.com/UlisesFTP/Rizx-Music-Player.git
cd Rizx-Music-Player/Proyecto
./gradlew assembleDebug            # builds without any configuration
./gradlew testDebugUnitTest        # JVM tests, no device needed
```

The optional account/sync features need public configuration values that are **not** in the
repository; without them the app builds and works with those screens hidden. See
[`docs/BUILD.md`](docs/BUILD.md).

## The workflow

Every change is a **slice**: one scoped piece of work, finished and recorded before the next.

```
Spec → Plan → Implement → Test → Report
```

- **Spec.** A new behaviour or a change to a contract (a model, a screen, a rule) starts with a short
  written statement: why, what the contract is, which invariants it keeps, how it is verified. For an
  external contribution the issue is that statement; keep it precise.
- **Plan.** Say which files you expect to touch and which tests you will add. Ask only for decisions
  that are the maintainers' to make; state your own assumptions.
- **Implement** inside the invariants (below) and the design rules (`docs/GOVERNANCE.md` §5).
- **Test.** JVM tests for every rule you introduce; an instrumented test when Room's schema moves;
  a device or emulator run when the claim is about a device.
- **Report.** The pull request description says what changed, what was verified and how, and what
  was left out. A red check is reported red.

## Rules that outrank convenience

These are enforced in review and, where possible, by tests. The full table with where each one is
pinned is in `docs/GOVERNANCE.md` §4.

- `domain/` is pure Kotlin: no Android, Media3, Retrofit or Room imports.
- `ProviderRef(provider, id)` is the identity of every upstream thing. Never a title, name or URL.
- Metadata and streaming stay separate; a stream URL is resolved just in time and **never persisted**.
- `PlaybackService` owns the one audio ExoPlayer. Composables never touch a player or a provider.
- A provider, plugin or cloud call that fails degrades alone; nothing crashes the app.
- Sources stay **keyless**: public endpoints and tokens a page hands to any visitor are fine; defeating
  an access control is not, however easy.
- No secret, key, credential or configuration value enters the repository, a test, a log or a commit
  message.
- No new dependency without saying why in the pull request, and never one that duplicates another.
  A new dependency lands in `docs/THIRD_PARTY_LICENSES.md` **and** in the in-app licence list
  (`ui/screens/LicenseData.kt`) in the same change.

## Code, strings, docs

- Kotlin, Jetpack Compose, Hilt. Follow the style of the surrounding file; comments explain *why*.
- Every user-visible string exists in **English, Spanish, Portuguese and French**
  (`res/values`, `values-es`, `values-pt`, `values-fr`). Missing translations fail review.
- Keep `Build.VERSION` checks in Android-only classes so the JVM-tested pipeline never branches on
  SDK level.
- A change that alters a screen, a setting or a contract also updates the document that describes it:
  [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md), [`docs/TECHNICAL_GUIDE.md`](docs/TECHNICAL_GUIDE.md)
  or [`docs/GOVERNANCE.md`](docs/GOVERNANCE.md). That is part of "done".

## The gate

Run it before opening the pull request and say in the description that you did:

```bash
cd Proyecto
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Instrumented tests (`./gradlew connectedDebugAndroidTest`) are required when a change touches Room
migrations. The same gate runs in GitHub Actions on every pull request.

## Pull requests

- One slice per pull request; keep unrelated refactors out.
- Fill the template: summary, the issue or spec it implements, the checklist.
- Commit subjects in English or Spanish, present tense, saying what changed for a listener or a
  developer. History is never rewritten after review starts; a fix is a new commit.
- Maintainers review for the invariants first, then for the change itself. Expect questions about
  identity, ephemeral URLs and failure isolation — they are the load-bearing parts.

## Using a coding agent

You are welcome to use Claude Code, Codex, OpenCode or any other agent. Point it at
[`AGENTS.md`](AGENTS.md) first; it contains the bootstrap order and the boundaries. **You** remain the
author: read what the agent wrote, run the gate yourself, and do not submit output you cannot explain.

## Licensing of contributions

Rizx Player is licensed under the GNU General Public License v3.0. By submitting a contribution you
agree that it is licensed under the same terms. Do not submit code you do not have the right to
license that way, and do not add upstream-attribution or "derived work" wording to the licence files,
the README, `docs/` or the About screen (see [`docs/LICENSING.md`](docs/LICENSING.md)).

## Reporting bugs and security issues

Bugs go to the issue tracker using the bug template (app version, Android version, device, steps,
and a `logcat` excerpt help enormously). Security problems follow [`SECURITY.md`](SECURITY.md) — please
do not file them as public issues.
