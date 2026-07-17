# Privacy Policy (draft) — Rizx Player

_Last updated: 2026-07-13 · applies to Rizx Player Android, version 0.1.0 (beta)_

> Draft for the public beta. Rizx Player ("the app") is an open-source, native Android music
> player and an independent fork of [nukeop/nuclear](https://github.com/nukeop/nuclear).

## Summary

- **No account, no sign-in.** The app has no user accounts and collects no personal profile.
- **No analytics, no ads, no tracking.** The app contains no analytics SDK, advertising SDK, or
  third-party tracker, and sends no telemetry to the developers.
- **No developer-operated backend.** We do not run any server that receives your data. The app is a
  standalone client.
- **Your library stays on your device.** Favorites, playlists, and settings are stored only in the
  app's local storage (Room / DataStore) on your device.

## Data the app processes

### On your device only
- **Favorites, playlists, and preferences** (theme, active providers) — persisted locally via Room
  and DataStore. Never transmitted by us. Removed when you clear app data or uninstall.
- **Playback/queue state** — held in memory and/or local storage on the device.

### Sent to third-party content providers (only when you use them)
When you search or play audio using a **real provider**, the app makes direct network requests to
that provider's public API. In this beta the only real provider is the **iTunes Search API**
(operated by Apple Inc.):

- **What is sent:** your search text and stream-resolution lookups (track ids), plus the standard
  metadata any HTTP request carries (your IP address and the app's `User-Agent`).
- **Why:** to return search results, resolve a playable preview URL, and stream a 30-second preview.
- **Who receives it:** Apple, under Apple's own terms and privacy policy. We do not control or
  receive that data.
- **When:** only while you are actively searching or playing with a real provider selected. The
  built-in **fake/offline providers** make no network requests.

No search text or listening activity is stored on any server operated by us — because we operate no
server.

## Permissions

- **INTERNET / ACCESS_NETWORK_STATE** — to reach content-provider APIs and stream audio.
- **FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK / WAKE_LOCK** — to keep audio playing in
  the background via a media session.
- **POST_NOTIFICATIONS** — to show the media playback notification (you may deny it; playback still
  works).

## Children

The app is not directed at children and collects no personal data from anyone.

## Your choices

- Use the **fake/offline providers** (Settings → Sources) to avoid all network requests.
- **Clear cache / app data** in Android Settings to erase locally stored favorites, playlists, and
  preferences.
- Uninstall to remove all locally stored data.

## Changes

This policy may change as real providers are added or if a network component is ever introduced (see
AGPL §13 assessment in `docs/specs/014-release-and-licensing-spec.md`). Material changes will be noted
in the app's About screen and the project repository.

## Contact

Open an issue in this project's source repository (linked from the app's **About** screen).
