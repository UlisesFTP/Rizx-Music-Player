# Rizx Player — user guide

_For Rizx Player 1.0.0 on Android 8.0 and newer. Every label below is the English one; the app is
also available in Spanish, Portuguese and French._

Rizx plays music from free public sources, plays the music already on your phone, works offline with
downloads, and needs no account. This guide walks every screen, control and setting, then what to do
when something does not work.

## Contents

1. [Installing and updating](#1-installing-and-updating)
2. [The four tabs](#2-the-four-tabs)
3. [Home](#3-home)
4. [Search, genres and Audio ID](#4-search-genres-and-audio-id)
5. [Playing music](#5-playing-music)
6. [Now Playing](#6-now-playing)
7. [Lyrics](#7-lyrics)
8. [Sound: equalizer, 8D, quality](#8-sound-equalizer-8d-quality)
9. [Animated covers](#9-animated-covers)
10. [Your library](#10-your-library)
11. [Playlists: yours, imported, shared](#11-playlists-yours-imported-shared)
12. [Downloads and music on your phone](#12-downloads-and-music-on-your-phone)
13. [Account and sync](#13-account-and-sync)
14. [Home-screen widgets](#14-home-screen-widgets)
15. [Plugins and sources](#15-plugins-and-sources)
16. [Settings, one row at a time](#16-settings-one-row-at-a-time)
17. [Languages and themes](#17-languages-and-themes)
18. [Permissions the app asks for](#18-permissions-the-app-asks-for)
19. [When something does not work](#19-when-something-does-not-work)
20. [Privacy: what stays on the phone](#20-privacy-what-stays-on-the-phone)

---

## 1. Installing and updating

1. Download `Rizx-<version>-release.apk` from the project's
   [Releases page](https://github.com/UlisesFTP/Rizx-Music-Player/releases/latest).
2. Open the file. Android asks once to allow installs from the app you opened it with (your browser
   or file manager). Confirm, then install.
3. Open Rizx. On Android 13 and newer it asks once for **notifications** — that is only so you can
   see the playback controls and download progress in the shade; saying no costs nothing else.

**Updating.** Rizx checks the project's releases about once a day and shortly after you open it. When
a newer version exists you get one notification, *Rizx x.y.z is available*, and the row **App
updates** in Settings › App reads *x.y.z available*. Tap either: the dialog shows what changed and
the size, **Download** fetches the file and verifies it, and **Install** hands it to Android, which
asks you to confirm. The first time, Android needs you to **Allow installs** from Rizx — the dialog
takes you to that switch and back. **Skip this version** hides that version until a newer one
appears; **Later** just closes the dialog. Nothing installs without your confirmation.

**Verifying a download.** The release page shows a SHA-256 digest next to the file; the app checks
it before offering to install. The signing certificate's fingerprints are printed in the README so
you can check an APK yourself.

## 2. The four tabs

The bar at the bottom has **Home**, **Search**, **Library** and **Settings**. Above it floats the
**mini-player** whenever something is loaded: cover, title, artist, elapsed and total time, a heart,
play/pause, and a thin red line you can tap or drag to seek. Tap the mini-player to open Now Playing.

## 3. Home

The greeting changes with the time of day; the three buttons on the right open Search, your liked
songs and your account.

**Tabs:** **All** (the overview), **For you** (only the rows built from your listening), **Songs**,
**Playlists**, **Albums**, **Artists** (each a grid of the current charts).

On **All**:

- **Continue listening** — six recent songs; the dice button plays a random one. Works offline.
- **Your mixes** — mosaic tiles built from what you play: *Your daily mix*, *On repeat*,
  *Rediscover*, *New to you*, *Around the world*, one mix per artist cluster. They cost no network:
  they are made from songs already known to the phone.
- **Mix ·**, **Because you like …**, **Similar to …** — rows seeded by your own artists.
- **Featured playlists**, **Playlists for you**, **Top songs**, **Top albums**, **Popular artists**,
  **New releases** — charts blended from Deezer, Apple Music, Spotify's editorial charts and YouTube
  Music. Each **See all** switches to the matching tab. Settings › Sources › **Home feed** narrows
  the whole feed to one platform.
- **Moods & genres** — station tiles; **See all** opens every station. A station is a live
  rotation: opening it shows what it is playing right now, and reopening later legitimately shows a
  different list.
- A card, *Recommendations from your region?*, appears once. It asks whether the charts may use your
  country, inferred from your SIM or device language — never your location. **Not now** keeps the
  charts global; you can change it later in Settings › Recommendations.

The feed opens instantly from the last one it drew and refreshes underneath; pull down to refresh.
Offline, the feed says so and keeps *Continue listening*.

## 4. Search, genres and Audio ID

Type in **Songs, artists, moods…**. Suggestions drop over the tabs: a clock is one of your past
searches, a magnifier a suggestion. Results come in five tabs — **Songs**, **Artists**, **Albums**,
**Playlists** (Deezer and YouTube) and **Underground** (YouTube and SoundCloud uploads that exist
nowhere else: remixes, edits, live takes, grouped by source). Tapping a song plays it and starts a
radio behind it; the heart likes it; the **+** menu offers **Play next** and **Add to queue**.

With the field empty:

- **Try searching** — pills for your recent searches and the artists you actually play, computed on
  the phone.
- **Browse all** — 28 tiles from **Charts** to **Kids**. A tile opens a **genre hub**: that genre's
  songs (playing one makes the whole genre your queue), playlists, artists and albums. Genres are
  addressed by the catalogue's own id, never by searching the word — so *Pop* shows pop, not songs
  titled "Pop".

The **queue** chip at the top right (a number) opens the full queue; it appears only when something
is queued.

**Audio ID** — the microphone button. Point the phone at the music and tap **Listen**: Rizx pauses
its own playback, listens for about ten seconds, turns the audio into a fingerprint *on the phone*
and discards the recording. A match shows the cover, title, artist, album, ISRC, label and release
year; **Play in Rizx** plays the recording it found in the catalogue, and when the match is not
confident enough Rizx says so and offers **Search in Rizx** instead of playing a wrong version.
Recognitions are kept under **Recognised before** (clear them with the bin icon). Backgrounding the
app cancels a capture; rotating the phone does not.

## 5. Playing music

- Tap any song anywhere. What you tapped from becomes the **context**: the album, the artist's
  songs, the playlist, your liked songs, recents, downloads or the local library — and **Next** /
  **Previous** move inside it.
- When a context runs out, or when you press **Start radio**, the queue refills itself endlessly.
  Settings › Recommendations › **Up next algorithm** picks the engine: Deezer (follows the artist),
  YouTube Music (follows the song, like autoplay), Apple Music, SoundCloud.
- **Shuffle** keeps the original order in memory, so turning it off restores the list exactly.
  **Repeat** cycles off → queue → this song.
- Playback continues in the background with controls in the notification shade and on the lock
  screen. Rizx remembers where you were: reopening the app after Android closed it returns to the
  same song at the same second.
- Songs you have played recently replay from a local cache without touching the network, and stay
  playable offline while they are in it (Settings › Data & storage › **Offline cache**).

**The queue.** From Now Playing, pull up **Up next** (or tap it) for the drawer: tap a row to jump
to it, ✕ removes it, long-press the grip and drag to reorder. The full **Queue** screen (from Search's
chip) adds **Save as playlist** and **Clear**.

## 6. Now Playing

Two arrangements, chosen in Settings › Appearance › **Player layout**:

- **Classic** — the cover fills the top of the screen with corner brackets, a floating toolbar (back,
  lyrics, menu) and a record label; below it the waveform, the times, the title, the transport, and a
  row with **Add to playlist** and **Like**.
- **Compact** — an ink stage: a toolbar with the song's thumbnail, a framed cover with a caption band
  (the album, or *RIZX VISUAL ARCHIVE*) and a record line (`REC / TRK 08-240 · STREAM · STEREO`);
  the title sits above the progress bar flanked by add-to-playlist and like, which buys the cover a
  row. Soft lights in the cover's own colours drift behind it (they hold still when Android's
  animations are off).

Everything else is the same in both:

- **Waveform seek bar** — the bars move with the music. Tap to jump; drag to scrub with a time bubble
  that follows your finger.
- **Transport** — shuffle, previous, play/pause (a spinner while the song resolves), next, repeat.
  Shuffle, play and repeat carry a small red marker showing their state.
- **Gestures on the cover** — swipe left/right for next/previous, swipe down to close the player,
  double-tap to like (a red heart stamps the cover).
- **Bottom bar** — **Nearby devices** opens Android's output panel (speaker, Bluetooth, Cast; on
  Android 9 and older, Bluetooth settings), **Up next · N** opens the queue drawer, **Start radio**
  seeds an endless radio from the current song.
- **Lyrics** (the speech-bubble button) opens the lyrics screen; **⋮** opens the menu.

**The ⋮ menu.** One download row, depending on state: **Download**, then **Download as…** which
unfolds the formats available on your phone (Original, Opus on Android 10+, MP3 · 320, FLAC when
available); while it runs, *Queued*, *Downloading 43%*, *Converting to MP3…*; afterwards
**Downloaded — remove**; on failure **Download failed — retry**. Then always: **Video preview**
(the animated cover, on/off for this song — *none for this song* when there is nothing to show),
**Smart 8D audio** (with its reason when it is on hold: *needs headphones*, *Android is already
spatializing*, *not available for this track*), **Download as 8D · MP3 320** (a second file rendered
with the effect; **Delete the 8D file** once it exists), and **Share** (title, artist and the
source's own link).

Hold the phone in landscape on a tablet or an unfolded foldable and the cover and the controls sit
side by side; phones stay upright.

## 7. Lyrics

The header shows the song, the source the words came from (*via LRCLIB*, *via NetEase*…), a magnifier
to **search for other lyrics**, and a sync toggle that appears when the lyric carries timings.

- **Karaoke.** With timed lyrics the active line fills in word by word — letter by letter where the
  source is that precise — and the list keeps it a little above centre. Tap any line to jump there.
  Drag to read ahead; auto-scroll resumes a few seconds after you let go, or at once with **Resume
  auto-scroll**.
- **Timing.** The strip under the words: **−0.5s** and **+0.5s** shift the lyrics earlier or later;
  the centre shows **IN SYNC** or the offset in red — tap it to reset. The offset is remembered per song.
- **Readings.** For lyrics in another script a row of chips offers **Original**, **Pronunciation**
  and **Translation** when the source has them (Rizx can also romanize on the device).
- **Search for other lyrics.** When the automatic match is wrong, search by *artist and song title*,
  pick a result (badged *Synced* or *Word*), and it is pinned for that song; the timing strip then
  reads **Tap to unpin**.
- The bottom transport lets you pause, skip and seek without leaving the screen.

Where the words come from: LRCLIB, NetEase, KuGou, Musixmatch and lyrics.ovh, all raced at once; the
most precise confident match wins, and a wrong recording (a live version, a cover, another language)
is rejected rather than shown. Settings › Appearance › **Lyrics visual quality** trades glow and
smoothness for battery.

## 8. Sound: equalizer, 8D, quality

**Equalizer** (Settings › Sound › Equalizer, or the AUTO chip). The preset strip — Flat, Bass, Treble,
Vocal, Loudness, Pop, Rock, Hip-hop, Electronic, Latin, R&B, Jazz, Classical, Acoustic, Metal,
Podcast, Night, Reset — applies curves adapted to the bands your phone actually exposes. **Shape of
the sound** is a radar: one vertex per band, wider meaning more presence. **Bands** are vertical
faders with half-decibel steps and a haptic detent; the switch on the right turns the whole effect
off. The equalizer needs a song playing.

**Automatic equalizer** (Settings › Sound) derives a curve per song from its genre and from the
recording itself, measured during the first seconds. While it is on the manual controls are
read-only and the strip shows *AUTO · genre*.

**Smart 8D audio** (Settings › Sound, or the ⋮ menu). An adaptive headphone spatialization: the
upper part of the mix travels around you inside a large simulated room, with front/back and height
cues, tuned per genre and refined from the song's own width, weight and tempo. It switches mid-song
with a short fade, stands down by itself on the phone's speaker or when Android is already
spatializing (Settings › **Avoid double spatialization**), and has deliberately no strength slider.
It is made for headphones, and it is not Dolby Atmos.

**Audio quality** (Settings › Sound):

- **Standard** — a conservative stream.
- **Best available** (the default) — the highest-quality compressed stream each source offers (Opus
  160 kbps / 48 kHz over AAC 128 kbps where both exist), with 32-bit float output.
- **Prefer Lossless (experimental)** — looks for a verified FLAC first, from a community index that
  comes as a plugin; falls back automatically. **Only look for Lossless on Wi-Fi** keeps the tens of
  megabytes per song off mobile data. Available only when an index plugin is installed.

**Show technical format** prints the codec, depth and sample rate under the player; the caption
under Audio quality names your current output device.

**Normalize volume** applies a fixed loudness boost. **Crossfade** and **Gapless playback**
(Settings › Playback) are both on by default: a two-second crossfade between songs, or a seamless
join when crossfade is off.

## 9. Animated covers

Settings › Appearance › **Animated covers**. When on, a muted, looping video plays behind the cover
in Now Playing (and behind the Home hero): Apple Music's motion artwork first, then TIDAL's video
covers, then the song's own music video from YouTube. Sources can be switched off one by one; a
**Quality** cap (Data saver, Automatic, High), a **Network** rule (Wi-Fi only, or Wi-Fi and mobile
data) and **Allow in battery saver** decide when it runs. Data saver always wins. **Last lookup** at
the foot of the dialog shows what happened for the current song — which source answered, or why
nothing did (*Skipped: mobile data*, *No video for this song*, *Found a video, but not this
recording*). Uploads that turn out to be still images are rejected, so what animates really animates.

## 10. Your library

**Library** opens on **All**: your playlists, songs you like, downloads and recents in one page,
with a liked-songs hero (**Play your liked songs**). The tabs — **Playlists**, **Liked**,
**Downloads**, **Recent**, **Local** — each have a filter field; whatever the filter leaves visible
is exactly what plays.

- **Liked** — every heart you tapped, anywhere. **Save all** copies them into a new playlist.
  Removing one offers **UNDO**.
- **Recent** — what you played, on this phone. **Clear** empties only this phone's history.
- **Downloads** — see §12.
- **Local** — see §12.

## 11. Playlists: yours, imported, shared

**New playlist** asks for a name. Songs join from any **Add to playlist** button, or from the queue's
**Save as playlist**. Open a playlist for its numbered list, a **Search in this list** filter,
**Download all**, and per-row **Remove** (on playlists you own); the header has **Rename** and
**Delete playlist**.

**Import.** The **Import** button takes a link from **Spotify, YouTube Music, YouTube or Deezer**
(Apple Music playlist pages work too), or a file: a Rizx export, a Nuclear JSON or an Exportify CSV.
Imports are complete whatever the length — Deezer, Spotify and YouTube page to the end — and when a
source truly cuts a list short, the playlist says so on its own screen. An imported playlist becomes
an ordinary one you can edit.

**Share or export** (the share icon on a playlist):

- **Rizx JSON** — a full-fidelity backup, readable by Rizx and compatible importers.
- **XSPF** — portable metadata with public catalogue links.
- **M3U8** — only the songs that have a safe public link (the sheet tells you how many were omitted).
- **Private link and QR** — an unlisted snapshot anyone with the link can open. It opens straight in
  Rizx when the app is installed; otherwise a page offers *Open in Rizx* and the download. Links last
  30 days for a signed-in account, 7 days for a guest, and **Revoke link** kills one at once. This
  option needs the app to be built with a share domain (see §13).

Portable copies never include downloads or temporary stream links.

## 12. Downloads and music on your phone

**Downloads.** Tap the download icon on any song, album or playlist. The format is set in
Settings › Downloads › **Download format**, or per song from the ⋮ menu:

| Format | What you get |
|---|---|
| **Original** | The bytes as they arrive (M4A from YouTube). Smallest effort, full cover art. |
| **Opus** | The best-per-megabyte stream, never re-encoded, saved as `.opus` with cover art. Android 10+. |
| **MP3 · 320** | Re-encoded on the phone for maximum compatibility; it cannot sound better than its source. |
| **FLAC when available** | A verified lossless file when the community index has one (25–30 MB per song); otherwise Original. |

Every file carries its cover, artist, album and year, so it looks right in any player. Downloads play
first, before any network request, and are the offline library.

**Save to the phone.** The first download asks: **Only in Rizx** (the song plays offline but no other
app sees it) or **Also in the phone's Music** (a copy lands in `Music/Rizx`, visible to every app,
taking twice the space). Change it later in Settings › Downloads; the Downloads tab shows which
files are *On the phone* and offers **Save** per row or **Save N to the phone** for the backlog.
Deleting a download inside Rizx keeps the phone copy. On Android 8 and 9 this needs the storage
permission, asked when you opt in.

**Local music** (Library › Local › **Open local music**). Five views: **Songs** (sort by title,
recently added, artist or duration; an A–Z rail when sorted by title; **Play all** and
**Shuffle**), **Playlists** (local-only lists), **Albums**, **Artists**, and **Files** — a file
explorer that opens single files or whole folders from anywhere (SD card, USB, other apps'
downloads) with no permission at all, and keeps them under **Recently opened**. The scanned views
ask for audio access the first time; declining keeps Files and Playlists working. Local songs are
first-class: like them, put them in playlists, queue them.

## 13. Account and sync

Rizx is complete without an account. Signing in (Settings › App › **Account and sync**, or the avatar
on Home) keeps **playlists, liked songs and your listening taste** the same on every device:

- **Continue with Google** is the sign-in method in this version.
- Every edit is journaled locally and uploaded within seconds; opening the app pulls what other
  devices did; while the app is on screen other devices catch up within seconds, and a check every
  few hours covers a phone left in a drawer. **Sync now** forces a run and shows how many changes wait.
- Taste adds up: a song played five times here and three times on a tablet is an eight-play song on
  both, and Home's personalised rows refresh when another device's history arrives.
- The phone stays the source of truth. Nothing waits on the network; offline the app is identical;
  **Sign out and keep local library** does exactly that. A phone that last synced with a different
  account asks first: **Merge into this account**, **Use the cloud copy** (the local library is kept
  for 30 days), or **Stay signed out**.
- **Delete cloud account** removes the cloud playlists, favorites, taste and links; the local library
  stays.
- Never uploaded: downloads, local files and paths, stream links, the queue, detailed history,
  recognition history, plugins, provider credentials.

A build made without the public cloud configuration shows *Cloud features are disabled in this
build* and hides the rest.

## 14. Home-screen widgets

Long-press the home screen › Widgets › Rizx:

- **Now playing** (4×2) — cover, title in dot-matrix type, artist, clock, a red dotted bar you can tap
  anywhere to seek, previous / play / next, ♥ and a microphone that opens Audio ID already listening.
- **Now playing · compact** (4×1) — cover, title, artist, transport, ♥.
- **Audio ID** (2×2) — tap the microphone to identify what is playing; the last match stays on the
  card with a play button.

They show the last song even with the app closed, and every button works from there.

## 15. Plugins and sources

Settings › Sources › **Plugins & sources**. The **Installed** tab lists Rizx's built-in providers by
kind with a health badge — for kinds with one active source (metadata, streaming, lyrics) tap one to
use it; for kinds that blend (charts, playlist import) toggle them — and any plugin you installed,
with **Update**, **Uninstall**, an on/off switch, and **Restart the plugin engine** if one stops
answering. The **Store** tab lists the Nuclear plugin catalogue (only what Rizx does not already do
natively and can actually run), anything shipped with the build, **Install from URL** for a plugin
zip, and **Add a registry** for another catalogue.

Plugins run in a sandbox with no access to your files or Android; the one thing they can do is fetch
web pages, through a guarded connection. A plugin that keeps failing is auto-disabled until you turn
it back on. Only install plugins you trust.

## 16. Settings, one row at a time

The page has a search field (**Search settings**) that matches titles, values and captions.

**Sound** — Equalizer · Automatic equalizer · Smart 8D audio · Avoid double spatialization (with 8D
on) · Normalize volume · Audio quality (§8).

**Playback** — Crossfade · Gapless playback.

**Appearance** — Theme (System / Light / Dark) · Player layout (Classic / Compact) · Lyrics visual
quality (Automatic / High / Battery saver) · Animated covers (§9).

**Sources** — Plugins & sources (§15) · Home feed (All combined, or one platform).

**Recommendations** — Up next algorithm (Deezer / YouTube Music / Apple Music / SoundCloud) ·
Regional recommendations (uses your country for charts; never location).

**Downloads** — Download format · Save to the phone (§12).

**Data & storage** — Data saver (lower bitrate, smaller covers, downloads wait for Wi-Fi; also
follows Android's own Data saver) · Offline cache (tap to cycle the size) · Clear cache (the offline
cache only; downloads are untouched).

**App** — App updates (§1) · Account and sync (§13) · Language (§17) · About Rizx (version,
licence, the source repository, open-source licences).

Every setting is saved as soon as you change it.

## 17. Languages and themes

**Language** — System default, English, Español, Português, Français. On Android 13 and newer the
choice also appears under Android's own per-app language page. **Theme** — System follows the phone;
Light is the warm *Paper* look; Dark is the near-black *Ivory* look. Both use the same type: DM Sans
for titles, a mono face for labels, dot-matrix numerals for times.

## 18. Permissions the app asks for

| When | Permission | Why |
|---|---|---|
| First launch (Android 13+) | Notifications | To show playback and download progress. Optional. |
| Opening Local › Songs/Albums/Artists | Audio files (Android 13+) or Storage (older) | To scan the phone's music. Files and Playlists work without it. |
| Turning on Save to the phone (Android 8–9 only) | Storage | To write into the public `Music/` folder. |
| Starting Audio ID | Microphone | For the seconds you asked for. Audio is fingerprinted in memory and discarded. |
| Installing an update | Install unknown apps (a one-time switch) | So Rizx can hand the new version to Android's installer. |

Rizx never asks for your location or your contacts, and never listens in the background.

## 19. When something does not work

- **A song will not play.** Sources are third-party and can be down; try again, or try the same song
  from another result. Settings › Sources shows a health badge per provider.
- **Search or the feed says you are offline.** Check the connection and pull to refresh. Downloads,
  the offline cache, *Continue listening* and your library keep working offline.
- **No lyrics, or the wrong ones.** Use the magnifier on the lyrics screen to search by artist and
  title and pin the right one; use −0.5s / +0.5s if they run early or late.
- **The cover does not animate.** Open Settings › Appearance › Animated covers and read *Last
  lookup*: it tells you which rule stopped it (mobile data, data saver, battery saver, weak
  connection) or that no video exists for that song. Keep the YouTube source on — it is the one
  that covers most songs.
- **8D says "on hold".** It needs each ear to get its own channel: use headphones, or turn off
  Android's own spatial audio (or the *Avoid double spatialization* switch).
- **Lossless is greyed out.** It needs a FLAC index plugin from the Store.
- **An update will not install.** A build you compiled yourself, or an old debug-signed test build,
  cannot be updated by the published release: uninstall it first. If Android refused, allow installs
  from Rizx when the dialog asks.
- **A widget shows an old song.** It updates on every change while the app or its playback service
  runs; tap it to open Rizx.
- **The app crashed.** Report it with the bug template on GitHub; the version is under Settings › App
  › About Rizx. Sensitive data never reaches the log, but a log can name the songs you played.

## 20. Privacy: what stays on the phone

- No tracking, no analytics, no ads, no telemetry.
- Listening history, taste, recognition history, downloads, the queue and the audio cache stay on
  the phone. Sync uploads only playlists, likes and aggregate taste, only when you sign in.
- Searches, lyrics, covers and streams are direct requests to the public services that serve them;
  they see your IP address and the app's name, and nothing about your account.
- The microphone opens only for a recognition you started.
- Once a day the app asks GitHub for its release list to offer updates; nothing about you is sent.

The full policy: [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md).
