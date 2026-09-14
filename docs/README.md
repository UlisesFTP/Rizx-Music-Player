# Rizx Player documentation

_Index reviewed on 2026-09-13 · Rizx Player 1.0.0 (versionCode 3)._

Everything about the project that is not code lives in this directory. Start with the reading order
for your role, then use the map. The Android Studio project is in `../Proyecto/`; when a historical
statement here conflicts with the shipped code, the code and the living documents below win.

## Reading order

| If you are… | Read, in this order |
|---|---|
| a listener, or explaining the app to one | [`USER_GUIDE.md`](USER_GUIDE.md), then [`FEATURES.md`](FEATURES.md) for the tour |
| a developer about to change code | [`../AGENTS.md`](../AGENTS.md) → [`CONTEXT.md`](CONTEXT.md) (header, then the tail) → [`GOVERNANCE.md`](GOVERNANCE.md) → [`TECHNICAL_GUIDE.md`](TECHNICAL_GUIDE.md) → the deep dive of your area |
| a coding agent (or the person driving one) | the same, and `GOVERNANCE.md` §11 first |
| building, signing or releasing | [`BUILD.md`](BUILD.md), then `TECHNICAL_GUIDE.md` §18–§20 |
| writing a plugin | [`plugins/PLUGIN_GUIDE.md`](plugins/PLUGIN_GUIDE.md), or [`plugins/PLUGIN_SPEC_FOR_AGENTS.md`](plugins/PLUGIN_SPEC_FOR_AGENTS.md) if you are an agent |
| deploying the optional backend or a share domain | `TECHNICAL_GUIDE.md` §11, [`BUILD.md`](BUILD.md) § Public runtime configuration, [`../share-site/README.md`](../share-site/README.md) |
| checking what the app does with data | [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md), [`../SECURITY.md`](../SECURITY.md) |
| checking licences | [`LICENSING.md`](LICENSING.md), [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md), [`../LICENSE`](../LICENSE), [`../NOTICE`](../NOTICE) |

## Map

### Living documents (kept current with every slice)

| File | What it is |
|---|---|
| [`CONTEXT.md`](CONTEXT.md) | Where the project stands: the state table, what is implemented and what is private, configuration names, the interfaces other clients depend on, and the dated running log (newest last). |
| [`GOVERNANCE.md`](GOVERNANCE.md) | The rulebook: roles, the workflow, the definition of done, the invariants with where each is enforced, design rules, release rules, where knowledge lives, the decision log, open items, the agent protocol. |
| [`TECHNICAL_GUIDE.md`](TECHNICAL_GUIDE.md) | The map of the code: layers, packages, the domain model, providers and resolution, the playback pipeline, every subsystem, the UI, persistence, network, build, tests, releasing, security, a glossary. |
| [`USER_GUIDE.md`](USER_GUIDE.md) | The product from the listener's side: installing and updating, every screen, control and setting, permissions, troubleshooting, privacy. |
| [`../AGENTS.md`](../AGENTS.md) | The short standing instructions for anyone touching the code — human or agent. |
| [`../README.md`](../README.md) | The repository's front page: what Rizx is, screenshots, features, download, build, documentation links. |

### Deep dives (single topics, linked from the guides)

| File | What it is |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The load-bearing rules in depth: identity, two-phase resolution, playback, the queue, downloads, canvas, lyrics, recommendations, the 8D engine, recognition, persistence, sync, widgets, testing. |
| [`PROVIDERS.md`](PROVIDERS.md) | The two provider contracts, the registry, every content source and how it stays keyless, the plugin runtime, how to add a provider. |
| [`FEATURES.md`](FEATURES.md) | The feature tour and the device-compatibility table. |
| [`BUILD.md`](BUILD.md) | Toolchain, local configuration, public runtime configuration, build types, release signing, Room schemas, tests, troubleshooting. |
| [`plugins/PLUGIN_GUIDE.md`](plugins/PLUGIN_GUIDE.md) · [`plugins/PLUGIN_SPEC_FOR_AGENTS.md`](plugins/PLUGIN_SPEC_FOR_AGENTS.md) | The plugin contract for authors, and the same as exact shapes for coding agents. |

### Policies and contribution

| File | What it is |
|---|---|
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | What the app processes, which services it contacts and why, permissions. |
| [`LICENSING.md`](LICENSING.md) · [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) | GPL-3.0 obligations, trademarks, Corresponding Source; every bundled dependency and its licence. |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) · [`../SECURITY.md`](../SECURITY.md) · [`../CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md) | How to contribute, how to report a vulnerability, how we treat each other. |
| [`../.github/`](../.github/) | The CI workflow (the gate on every pull request), issue templates, the pull-request template. |
| [`assets/`](assets/) | The README banner and screenshots. |

### Outside this directory, and private

- `../share-site/` — the static files a share-link host serves (landing page, App Links statement).
- The maintainer's private design record — one spec per slice (`specs/`), one ADR per trade-off
  (`adr/`), checklists and the operational agent context — is git-ignored. Its decisions are restated
  in `GOVERNANCE.md` §9 and `CONTEXT.md`; the Corresponding Source of the app is complete without it.
- `../supabase/` (untracked) — the optional backend's migrations and Edge Functions; a deployment
  state, not part of the app.

## Conventions for writing here

- English, plain, one idea per sentence; dates as `YYYY-MM-DD`; numbers with what produced them.
- No configuration value, token, key, keystore, stream URL or personal data, ever — names only.
- No link to a file that is not in the repository.
- `CONTEXT.md`'s log is append-only at the tail, newest last; `GOVERNANCE.md`'s decision log is
  append-only, a reversed decision gets a new line.
- For a release, update `CONTEXT.md`'s state table, `BUILD.md`, the version and date in the privacy
  and licensing documents, and the in-app licence data (`ui/screens/LicenseData.kt` **and**
  `THIRD_PARTY_LICENSES.md` — the in-app list is what a user of the APK sees).
