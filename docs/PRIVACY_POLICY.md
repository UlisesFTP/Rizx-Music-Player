# Privacy Policy — Rizx Player

_Last reviewed: 2026-08-25 · applies to Rizx Player Android, version 1.0.0_

> Rizx Player ("the app") is an open-source, native Android music player. This policy describes what the
> app processes, where it goes, and what stays on your device.

## Summary

- **No account is required.** Every feature of the player works without signing in. An account is an
  optional extra for keeping your library the same on several devices and for sharing playlist links.
- **No analytics, no ads, no tracking.** The app contains no analytics SDK, advertising SDK, or
  third-party tracker, and sends no telemetry to the developer.
- **Your library and your listening history stay on your device** unless you sign in. Favorites,
  playlists, settings, downloads, and the listening log that powers recommendations are stored only in
  the app's local storage (Room / DataStore / files).
- **If you sign in**, your playlists, favorites and listening *counters* are stored in a backend
  operated for this app on Supabase, under your account only, so your other devices can read them. What
  is never uploaded is listed below.

## Data the app processes

### On your device only

- **Favorites, playlists, and preferences** (theme, language, active providers, playback options) —
  persisted locally via Room and DataStore. Removed when you clear app data or uninstall.
- **Listening log** — plays, completions, skips, listened time and time-of-day, kept locally to build
  the daily mixes and "Similar to …" rows. The detailed log never leaves the device; if you sign in, only
  per-song **counters** (first play, last play, play count, skip count) are synced.
- **Playback/queue state** — held in memory and/or local storage on the device, and shown on the home
  screen widgets you choose to add. The queue is never uploaded.
- **Downloads** — stored in the app's private storage. If you opt in to "save to phone", a copy is
  published into the shared `Music/Rizx` folder; those copies are ordinary files on your device, visible
  to other apps, and **remain after uninstalling** until you delete them. Downloads are never uploaded.
- **Region inference** — for optional regional charts the app reads the SIM/locale country **on the
  device** (it asks in-app first; no OS permission and no lookup service are involved).
- **Recognition history** — for each song you identify, the title, artist, album, ISRC, cover URL and the
  matched track are kept locally (capped at 200 entries, individually removable and clearable in one
  tap). **No captured audio and no fingerprint is stored**, in this history or anywhere else, and the
  history is never uploaded.
- **Plugins** you install, and the providers you enable, stay a local setting.

### Sent to third-party content providers (only when you use them)

When you search, play, fetch lyrics, artwork or animated covers, or import a playlist, the app makes
direct network requests to the relevant provider's public endpoints. Depending on the feature these
are: **Deezer**, **Audius**, **Apple** (iTunes Search API, editorial RSS, motion artwork), **TIDAL**
(video covers), **YouTube / YouTube Music** (Google), **SoundCloud**, **Spotify** (public playlist
embeds, only for imports and editorial charts), **LRCLIB**, **NetEase**, **KuGou**, **Musixmatch**,
**lyrics.ovh**, and **Wikipedia**.

- **What is sent:** your search text or the track/album/artist being looked up, plus the standard
  metadata any HTTP request carries (your IP address and the app's `User-Agent`).
- **Why:** to return search results, resolve playable streams, and fetch covers, lyrics, charts and
  artist pages.
- **Who receives it:** the respective service, under its own terms and privacy policy. The developer
  does not control or receive that data.
- **When:** only while you actively use a feature backed by that service. Disabled providers are never
  contacted.

### Microphone — music recognition

When, and only when, you start a recognition from the Audio ID screen or the Audio ID widget:

- **What the app does with the audio:** roughly ten seconds are captured, held **in memory**, and
  converted on your device into an acoustic fingerprint — a compact list of spectral peaks from which
  speech, or any audio at all, cannot be reconstructed. The audio buffer is then discarded. **No
  recording is written to storage, and no recording is ever transmitted.**
- **What is sent:** the fingerprint, the length of the sample, your device's timezone, and a
  **fixed all-zero location**. The app neither requests nor uses your real location, and no location
  permission is declared.
- **Who receives it:** an unofficial, keyless Shazam-compatible recognition endpoint operated by Apple,
  under its own terms and privacy policy. The request carries the standard metadata any HTTP request
  does (your IP address and the app's `User-Agent`, which honestly identifies Rizx). The developer
  receives nothing.
- **Recognition is not local.** The fingerprint is computed on the device, but the match is looked up
  remotely — the app does not claim otherwise.
- **No background or always-on listening.** There is no recognition service, no Quick Settings tile and
  no listening at boot. The widget's microphone only opens the Audio ID screen; the microphone is open
  only while that screen is in front of you and you have started a recognition, and cancelling closes it
  immediately.
- **The song Rizx is playing is never sent.** Recognition only happens when you start it, and playback
  is paused first.

### Optional account, sync and share links

Signing in is a choice made on the **Account & sync** screen; nothing below happens until you do.

**Signing in.** You can sign in with **Google** (through Android's Credential Manager — Google receives
the sign-in request under Google's privacy policy; the app receives your Google account's e-mail address,
name and avatar URL as part of the identity token) or with a **passwordless e-mail code** (your e-mail
address is stored as the account identifier and a six-digit code is sent to it). Access and refresh
tokens are stored on the device encrypted with a key in the Android Keystore.

**What is stored in the backend while you are signed in.** A Supabase project operated for this app,
with data isolated per account by database row-level security:

- your account identity (Supabase user id, e-mail address, sign-in provider);
- your **playlists** (names, order, and the descriptive metadata of each item: title, artists, album,
  duration, cover URL and the provider identity of the track);
- your **favorites** (track, album and artist identities with descriptive metadata);
- your **listening counters** per song, tagged with a random per-installation **device id** (first
  play, last play, play count, skip count) — so each device's history is kept separately and added up;
- bookkeeping needed to make sync reliable: a change revision per entry, a 30-day ledger of operation
  ids used to make retries idempotent, and a 30-day recovery snapshot of a playlist that a newer
  version replaced.

**What is never uploaded**, signed in or not: downloads and local files, on-device paths, resolved
stream URLs, the play queue, the detailed listening log, the recognition history, installed plugins,
and any provider credential.

**How it moves.** The app sends its pending changes and pulls the others' in one HTTPS request to the
backend, when you sign in, when the app starts, shortly after you edit something, when it returns to the
foreground, and every few hours in the background. While the app is on screen it also keeps a
WebSocket open to the backend so that it learns within seconds that another of your devices changed
something; that channel carries only a revision number and a device id, never library data, and it is
closed when the app leaves the screen. **Data saver** pauses the upload of listening counters on
metered connections.

**Share links.** Sharing a playlist as a link or QR uploads a **snapshot** of that playlist (its items'
descriptive metadata — no local paths, no stream URLs, no account or device identifiers) to the backend,
addressed by a random token; the backend stores only a hash of the token. Anyone who has the link can
read the snapshot until it expires (7 days for a guest session, 30 days for a signed-in account) or you
revoke it from the playlist's share sheet. If you share without an account, the app creates an
**anonymous guest session** in the backend for that purpose only (no e-mail, no name — a random id),
and that session's links can be transferred into your account if you sign in later. Opening a link in the app
downloads the snapshot and imports it as a local playlist; nothing about *you* is sent when you open
someone else's link.

**Signing out** stops syncing and **keeps your local library** on the phone. **Delete cloud account**
on the Account & sync screen revokes your sessions, deletes your synced data and share links from the
backend, and then deletes the account itself; the local library is untouched. Deleting the app does
not delete a cloud account — sign in on any device and delete it from there.

**Where the backend runs.** The backend is a Supabase project (managed PostgreSQL, authentication and
serverless functions hosted by Supabase Inc.) configured by the app's developer; Supabase's
infrastructure processes the data above under Supabase's own privacy policy. No other party receives
it, and it is not used for anything except giving your own devices access to your own library.

**Plugins:** if you install a plugin, that plugin can make its own `fetch` requests to the source it
implements (the sandbox has no filesystem or Android access, but outbound destinations are not
allow-listed). Which plugins are installed and enabled is always visible in the Plugins screen, and a
disabled plugin makes no requests.

No search text, playback activity or listening detail is ever stored on a server for this app — only
the account data listed above, and only when you sign in.

## Permissions

- **INTERNET / ACCESS_NETWORK_STATE** — to reach content-provider APIs, stream audio, and sync an
  optional account (and to detect metered connections for the data saver).
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

The home screen widgets need no permission of their own: they show the current song and control playback
through the app's own media session.

## Children

The app is not directed at children and collects no personal data from anyone who does not choose to
sign in.

## Your choices

- **Stay signed out.** The player is complete without an account; nothing about your library leaves the
  device.
- **Disable any provider or plugin** from Settings → Plugins & sources — disabled sources are never
  contacted.
- **Data saver** limits network use on metered connections.
- **Sign out** to stop syncing while keeping the local library; **delete the cloud account** to erase
  everything stored for you in the backend.
- **Revoke a share link** from the playlist's share sheet at any time; links also expire on their own.
- **Clear cache / app data** in Android Settings to erase locally stored favorites, playlists,
  preferences, the listening log, the recognition history, session tokens, and downloads.
- **Clear the recognition history** from the Audio ID screen, or remove single entries, without touching
  anything else. Revoking the microphone permission in Android Settings disables recognition entirely.
- Uninstall to remove all app data; files you chose to save into `Music/Rizx` stay until you delete
  them, and a cloud account stays until you delete it.

## Changes

This policy changes when providers are added or the account features change. Material changes are
noted in the app's About screen and in the project repository.

## Contact

Open an issue in this project's source repository (linked from the app's **About** screen).
