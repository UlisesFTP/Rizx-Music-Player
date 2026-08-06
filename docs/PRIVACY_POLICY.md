# Privacy Policy — Rizx Player

_Last updated: 2026-08-06 · applies to Rizx Player Android, version 0.2.0_

> Rizx Player ("the app") is an open-source, native Android music player and an independent
> reimplementation of [nukeop/nuclear](https://github.com/nukeop/nuclear)'s business logic.

## Summary

- **No account, no sign-in.** The app has no user accounts and collects no personal profile.
- **No analytics, no ads, no tracking.** The app contains no analytics SDK, advertising SDK, or
  third-party tracker, and sends no telemetry to the developers.
- **No developer-operated backend.** We do not run any server that receives your data. The app is a
  standalone client.
- **Your library — and your listening history — stay on your device.** Favorites, playlists, settings,
  downloads, and the listening log that powers recommendations are stored only in the app's local
  storage (Room / DataStore / files) on your device.

## Data the app processes

### On your device only

- **Favorites, playlists, and preferences** (theme, language, active providers, playback options) —
  persisted locally via Room and DataStore. Never transmitted by us. Removed when you clear app data or
  uninstall.
- **Listening log** — plays, completions, skips, listened time and time-of-day, kept locally to build
  the daily mixes and "Similar to …" rows. It never leaves the device.
- **Playback/queue state** — held in memory and/or local storage on the device.
- **Downloads** — stored in the app's private storage. If you opt in to "save to phone", a copy is
  published into the shared `Music/Rizx` folder; those copies are ordinary files on your device, visible
  to other apps, and **remain after uninstalling** until you delete them.
- **Region inference** — for optional regional charts the app reads the SIM/locale country **on the
  device** (it asks in-app first; no OS permission and no lookup service are involved).
- **Recognition history** — for each song you identify, the title, artist, album, ISRC, cover URL and the
  matched track are kept locally (capped at 200 entries, individually removable and clearable in one
  tap). **No captured audio and no fingerprint is stored**, in this history or anywhere else.

### Sent to third-party content providers (only when you use them)

When you search, play, fetch lyrics or artwork, or import a playlist, the app makes direct network
requests to the relevant provider's public endpoints. Depending on the feature these are: **Deezer**,
**Audius**, **Apple** (iTunes Search API, editorial RSS), **YouTube / YouTube Music** (Google),
**SoundCloud**, **LRCLIB**, **NetEase**, **KuGou**, **Musixmatch**, **lyrics.ovh**, and **Wikipedia**.

- **What is sent:** your search text or the track/album/artist being looked up, plus the standard
  metadata any HTTP request carries (your IP address and the app's `User-Agent`).
- **Why:** to return search results, resolve playable streams, and fetch covers, lyrics, charts and
  artist pages.
- **Who receives it:** the respective service, under its own terms and privacy policy. We do not
  control or receive that data.
- **When:** only while you actively use a feature backed by that service. Disabled providers are never
  contacted.

### Microphone — music recognition

When, and only when, you start a recognition from the Audio ID screen:

- **What the app does with the audio:** roughly ten seconds are captured, held **in memory**, and
  converted on your device into an acoustic fingerprint — a compact list of spectral peaks from which
  speech, or any audio at all, cannot be reconstructed. The audio buffer is then discarded. **No
  recording is written to storage, and no recording is ever transmitted.**
- **What is sent:** the fingerprint, the length of the sample, your device's timezone, and a
  **fixed all-zero location**. The app neither requests nor uses your real location, and no location
  permission is declared.
- **Who receives it:** an unofficial, keyless Shazam-compatible recognition endpoint operated by Apple,
  under its own terms and privacy policy. The request carries the standard metadata any HTTP request
  does (your IP address and the app's `User-Agent`, which honestly identifies Rizx). We receive nothing.
- **Recognition is not local.** The fingerprint is computed on the device, but the match is looked up
  remotely — the app does not claim otherwise.
- **No background or always-on listening.** There is no recognition service, no Quick Settings tile and
  no listening at boot; the microphone is open only while the Audio ID screen is in front of you and you
  have started a recognition, and cancelling closes it immediately.
- **The song Rizx is playing is never sent.** Recognition only happens when you start it, and playback
  is paused first.

**Plugins:** if you install a plugin, that plugin can make its own `fetch` requests to the source it
implements (the sandbox allows network access only). Which plugins are installed and enabled is always
visible in the Plugins screen, and a disabled plugin makes no requests.

No search text or listening activity is stored on any server operated by us — because we operate no
server.

## Permissions

- **INTERNET / ACCESS_NETWORK_STATE** — to reach content-provider APIs and stream audio (and to detect
  metered connections for the data saver).
- **FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK / WAKE_LOCK** — to keep audio playing in the
  background via a media session.
- **FOREGROUND_SERVICE_DATA_SYNC** — to keep a download batch alive with a progress notification.
- **POST_NOTIFICATIONS** (Android 13+) — to show the playback and download notifications (you may deny
  it; playback and downloads still work).
- **MODIFY_AUDIO_SETTINGS** — for the equalizer / audio effects.
- **RECORD_AUDIO** — only for music recognition, and only requested the first time you start one. Never
  used in the background; see [Microphone](#microphone--music-recognition) above. Denial only disables
  that one screen.
- **READ_MEDIA_AUDIO** (Android 13+) / **READ_EXTERNAL_STORAGE** (Android 12 and below) — only to scan
  your own music library when you open the Local section; denial is non-fatal.
- **WRITE_EXTERNAL_STORAGE** (Android 8–9 only) — only to publish downloads into the shared `Music/`
  folder if you opt in; newer Android versions need no permission for this.

## Children

The app is not directed at children and collects no personal data from anyone.

## Your choices

- **Disable any provider** (Settings → Sources) or any plugin — disabled sources are never contacted.
- **Data saver** limits network use on metered connections.
- **Clear cache / app data** in Android Settings to erase locally stored favorites, playlists,
  preferences, the listening log, the recognition history, and downloads.
- **Clear the recognition history** from the Audio ID screen, or remove single entries, without touching
  anything else. Revoking the microphone permission in Android Settings disables recognition entirely.
- Uninstall to remove all app data; files you chose to save into `Music/Rizx` stay until you delete
  them.

## Changes

This policy may change as providers are added or if a network component is ever introduced (which would
also trigger the AGPL §13 source offer — see [LICENSING.md](LICENSING.md)). Material changes will be
noted in the app's About screen and the project repository.

## Contact

Open an issue in this project's source repository (linked from the app's **About** screen).
