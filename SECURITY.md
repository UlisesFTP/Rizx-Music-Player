# Security policy

## Supported versions

Rizx Player is distributed as a signed APK from this repository's
[GitHub Releases](https://github.com/UlisesFTP/Rizx-Music-Player/releases). Only the **latest
release** receives fixes; the app checks for a newer release itself and offers the update in
Settings › App (see [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) § Updates).

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

1. Use GitHub's private vulnerability reporting for this repository (**Security → Report a
   vulnerability**) if it is enabled.
2. If it is not, open an issue titled `Security: contact request` **without any details** and a
   maintainer will reach out with a private channel.

Include what you can: the app version (Settings › App › About), the Android version, steps to
reproduce, and the impact you believe it has. You will get an acknowledgement, and a fix or a
reasoned answer; credit is given in the release notes unless you prefer otherwise.

## What is in scope

- The Android application in `Proyecto/` and every APK published on the Releases page.
- The share-link landing page in `share-site/`.
- The in-app update flow (release lookup, APK download and verification, the hand-off to Android's
  installer).
- The plugin sandbox (QuickJS runtime, host bridge, `fetch` guard) and the playlist-import parsers.

## What is not

- The third-party services the app reads from (Deezer, Audius, Apple, YouTube, SoundCloud, the lyrics
  services, GitHub, the recognition endpoint…). Report those to their owners.
- A self-hosted backend for the optional account/sync features. The schema, policies and functions
  are the deployer's responsibility; the app ships only publishable coordinates.
- Findings that require a rooted device, a modified APK, or a plugin the user chose to install.

## Facts that shape the threat model

- The APK contains **no secrets**: no API keys, tokens, client secrets or signing material. Every
  configuration value it reads is public by design.
- Stream URLs are ephemeral and never stored. Resolved streams, the queue and recognition history
  never leave the phone.
- Downloads and the audio cache live in app-private storage; "Save to the phone" copies into the
  shared `Music/Rizx` folder only at the user's request.
- Updates are verified against the SHA-256 digest GitHub publishes for the release asset, and
  Android additionally refuses any APK not signed with the project's release key. Install is
  always a user-confirmed action in the system installer.
- Plugins run in an isolated JavaScript runtime with no filesystem or Android API access; the one
  capability they keep is outbound `fetch` through a guarded bridge.

The release signing certificate's fingerprints are published in the README so a downloaded APK can
be checked with `apksigner verify --print-certs`.
