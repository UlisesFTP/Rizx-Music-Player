# Features

_Current feature inventory: 2026-08-25 · Rizx Player 1.0.0_

A tour of what Rizx Player does. Everything below is built on the shared domain pipeline described in
[ARCHITECTURE.md](ARCHITECTURE.md), so features compose cleanly — a local file, a downloaded track, and a
Deezer track are all just `Track`s with different `ProviderRef` sources.

## Playback

- **Background playback** via a `MediaSessionService`-owned ExoPlayer, with a **system media notification**
  and lock-screen transport controls.
- **Now Playing** — artwork with **swipe-to-skip** and **double-tap-to-like** gestures, a live spectrum
  **waveform seek bar** with a scrub time bubble, shuffle & repeat, an output-device switcher, a radio
  button, and an **Up-Next drawer** that is a real queue manager: pull it up, reorder, remove, or tap any
  song to jump to it.
- **Mini-player** — a floating bar across the app that expands into Now Playing.
- **Gapless & crossfade** — volume-envelope fades between tracks.
- **Optional loudness boost** — the setting currently applies a fixed +6 dB `LoudnessEnhancer` gain. It
  is not per-track LUFS measurement or true volume normalization.
- **Adaptive quality & Hi-Res mode** — stream quality follows current network conditions; an optional
  max-quality mode prefers Opus 160 kbps / 48 kHz over AAC 128 kbps, forces **32-bit float** PCM output,
  and Settings shows a live readout of what the current output path (DAC/headset) actually supports.
- **Automatic equalizer** — a per-song EQ curve: a genre baseline refined by measuring the track's own
  spectrum, applied mean-zero with boost trim. When it is off, the manual equalizer offers 17 localized
  listening profiles whose curves adapt to the real frequency ranges and gain limits exposed by the device.
- **Synced & karaoke lyrics** — timed lyrics with word-by-word / letter-by-letter highlighting where a
  source carries that resolution (LRCLIB, NetEase, KuGou, Musixmatch — all keyless), falling back to
  line-synced or prose lyrics otherwise.
- **Animated covers** — purpose-made motion album artwork from Apple Music and TIDAL behind Now Playing,
  falling back to the song's own (muted) music video when no animated album cover exists, with an
  anti-static filter that rejects still-image uploads; per-source toggles and network/quality/battery
  policies live in Settings.
- **Instant transport** — resolved stream URLs are cached per track identity and the next queue item is
  prefetched, so skips, seeks and rewinds are near-instant.
- **Audio cache** — recently played songs replay from a local Media3 cache (keyed by track identity, never
  by URL) and play offline without re-resolving.
- **Resume after process death** — reopen the app and it returns to the last track at the exact second
  (identities only are persisted, never ephemeral stream URLs).

## Queue & radio

- **Contextual Next/Prev** — navigation traverses whatever you started from: an album, an artist, a
  playlist, your Liked songs, Recently Played, Downloads, or the Local library.
- **Endless radio** — seed a radio from the current song or an artist; the queue auto-refills from a
  YouTube Music mix or a Deezer artist radio (the algorithm is selectable in Settings).
- **Shuffle** with faithful un-shuffle (restores the original order, even with duplicate entries).
- **Repeat** — off / one / all.

## Search

Tabbed search across sources:

- **Songs** — track search.
- **Artists** / **Albums** — dedicated entity search.
- **Playlists** — Deezer + YouTube playlists (Spotify playlists remain importable by URL).
- **Underground** — exclusive/independent material surfaced from YouTube (remixes, edits) and SoundCloud,
  grouped by source.
- **History & suggestion pills** — recent searches and artists you actually played come back as one-tap
  pills, computed entirely on-device (zero network, only deliberate searches are recorded).

**Browse by genre, not by the genre's name.** With the field empty, Search shows a wall of 28 tiles —
the catalogue's whole published genre list (Pop, Hip-Hop, Metal, Jazz, Salsa, Cumbia, Brazilian,
African, Indian, Soundtracks, Kids…) led by the all-genres **Charts**. A tile opens a **genre hub**:
that genre's songs, playlists, artists and albums, with the songs list becoming the queue so next/prev
stay inside the genre.

Each tile carries a genre **id**, never a query. Searching a catalogue for the word "Pop" returns songs
*titled* Pop and artists *named* Pop Smoke; a genre is a facet of the catalogue, and only the provider
that owns the catalogue can group by it. The wall itself makes **no network call at all** — ids and
artwork are inlined, so the app's most-opened screen never waits or jumps.

One deliberate exception to "take what the provider returns": the source's own per-genre **artists**
list is not genre-filtered — Metal, Jazz, Classical and an id that does not exist all answer with the
same regional top-artist list. The hub therefore derives its artists from the genre's own charting
tracks and albums, which is why Metal shows AC/DC and Metallica.

## Music recognition (Audio ID)

Identify a song playing near you. Reached from the microphone button in the Search header — one entry
point, no extra navigation.

**What happens, in order.** The microphone is requested only when you start a recognition, never at
launch. Rizx's own playback pauses first (a phone next to the speaker it is driving would otherwise
recognise Rizx). About ten seconds of mono 16-bit audio is captured — at 16 kHz directly where the
device allows it, otherwise resampled through a band-limited filter — and turned into a **Shazam-
compatible acoustic fingerprint on the device**. Only that fingerprint, the sample duration, the device
timezone and a neutral all-zero location are sent. **The audio itself never leaves memory** and is never
written to storage.

**Finding it in Rizx.** A match is not simply searched for by name — that is how a recognition turns
into a lyric video or a karaoke backing track. Three rungs, exact first:

1. **ISRC** — the recording's own identifier, resolved against Deezer's identity endpoint.
2. **Apple `adamid`** — the other exact identifier the service publishes, resolved through iTunes and
   then verified against what was heard.
3. **Scored search** — title and lead artist, with candidates scored on title, artist billing, album and
   ISRC. Version words (`live`, `remix`, `karaoke`, `sped up`…) must match on both sides, so a live take
   never answers for a studio recording.

Below the confidence threshold nothing is played: the match is shown with a **Search in Rizx** action
that opens Search with the song already typed in. Everything the service's links point at other than
those two identifiers — its Spotify, YouTube Music and Deezer links — are *search* deeplinks built from
the title, so Rizx does not pretend they are identifiers.

**While you are elsewhere, nothing listens.** The microphone closes the moment you cancel, navigate
away, or send the app to the background — and a screen rotation deliberately does *not* count, so
turning the phone mid-capture does not throw the recording away.

**History.** Each recognition is kept locally (Room, capped at 200 and prunable) as an *occasion*: the
same song identified twice is two entries. Stored are the title, artist, album, ISRC, cover URL and the
resolved track — **never** audio, never the fingerprint, never the service's response body. Tapping an
entry plays it, or searches for it when it was never resolved.

> The recognition backend is an **unofficial, undocumented** endpoint. It takes no key and no account,
> and Rizx identifies itself honestly to it rather than imitating another device. It can change or stop
> answering at any time; when it does, recognition reports an ordinary error and the rest of the app is
> unaffected.

## Home

A streaming-grade feed, rendered progressively from a disk cache so a warm start paints instantly:

- **Continue listening** speed dial (with a surprise-me die) on the overview tab.
- A dedicated **For you** tab with all recommendation rows; the overview keeps only the first three.
- **Three daily mixes** of up to 12 songs, built from your own listening log (see Recommendations).
- **"Similar to …"** rows anchored on artists you play, a **mood/genre station grid**, featured cards with
  preview, editorial playlists, charts, new releases, and mosaic tiles.
- A **feed source selector** — Deezer, Apple, Spotify editorial/charts, YouTube Music, SoundCloud
  **New & hot**, or a weighted blend. Compact rows show 10 items while full tabs use deeper source lists.
- Rows announce themselves from local taste before any network call, so the layout doesn't jump while
  content fills in.
- Pull-to-refresh keeps the current feed visible, each tab remembers its own position, and cards display
  their actual source instead of presenting blended content as one catalogue.

**Mood & genre stations open, they don't fire.** The grid at the foot of the feed is the same mosaic as
Search's browse wall — the provider ships a cover for every station and the app used to throw it away —
and a tile opens a **station hub** showing what that station is playing right now, so you can see the
list before committing to it and get a retry instead of a dead tap when you are offline. Playing any row
makes the whole fetched list the queue.

Home previews twelve of them with **See all** behind it; the provider publishes ~75, of which the app
used to show ten. A station is a live rotation rather than a fixed playlist — two consecutive fetches
share only about half their tracks — so the hub plays exactly the list it showed you, and reopening it
legitimately offers a different one.

## Smart 8D audio

Adaptive stereo spatialization, off by default, toggled from the player's overflow menu or from
Settings → Playback. It applies to the song already playing — the change is a fade of under a second,
not a restart.

**What it actually is.** Everything above the crossover is taken out of the mix and sent travelling
around the listener — equal-power panning, a sub-millisecond interaural delay, head-shadow filtering, a
front/back spectral cue and a little crossfeed — inside a large simulated room with a 42 ms pre-delay
and a tail of up to three and a half seconds. The front/back cue is what makes the path a circle rather
than a line: panning, delay and head shadow are all symmetric about the ears, so without it a sound
behind the listener is indistinguishable from one in front, and half of every orbit is wasted. The
outer ear is what tells them apart in life — it resonates around 3.5 kHz for sound from the front and
shadows that band and everything above it for sound from behind — so a bell swings from boost to cut
across the orbit and the top end rolls off as the source passes behind.

**And height.** The ring is inclined rather than flat: the sound climbs as it passes behind the listener
and comes back down in front. Elevation has no interaural cue at all — a source above and a source
ahead reach both ears identically — so what carries it is the *pinna notch*, the dip the folds of the
outer ear carve into arriving sound, whose frequency rises with the source. Sweeping that notch between
roughly 6 and 11 kHz is the entire height cue. The ambience leans with the source as well, because a
tail that stays put while the music moves is an anchor: it reads as a sweep across a fixed room rather
than as something going round you.

**Two layers, not one.** The recording's own width — everything the engineer placed away from the
centre, the guitars, pads and backing vocals — gets its own place in the ring, half a turn from the
middle of the mix, with its own front/back and height cues. One thing moving is a sweep; two things
moving in different places is a space. It travels by shifting its balance rather than by being panned,
so the width itself is kept rather than spent on the movement. Not a copy added on top: the original is removed as the moved version is put back, which
is the difference between the song itself travelling and a halo drifting around a song that stays put.
Everything below the crossover stays home, so the low end keeps its weight. It is **not** eight of
anything, not Dolby Atmos, not multichannel, not a measured HRTF, and not a quality improvement. It is a
*parametric approximation* of the cues an HRTF encodes — a real one is a set of measured impulse
responses per direction, per head, and this app ships no such dataset. A finished stereo
master cannot have its voice, drums and guitars moved separately without stem separation, so what
travels is the whole upper band together, with as much of the recording's own width preserved
underneath as the mix can spare. Made for headphones.

**Adaptive per song.** A profile arrives immediately from the track's genre, so the effect is there
from the first bar; meanwhile the recording itself is measured — stereo width and correlation, the
low/mid/high balance, crest factor, onset density and tempo — and the profile is refined and then
cached, so a second listen starts where the first finished. Bass-heavy masters raise their crossover
and calm down, already-wide mixes are left more of themselves, dense modern masters get less ambience,
and the tempo only sets the orbit's speed when the estimate is confident enough to act on. There is
deliberately **no strength control**: three of them meant two settings that were wrong for whoever
picked them, so the per-genre profiles are simply tuned to be worth switching on. Speech is the one
thing kept nearly still — an arena is the last place you want a podcast — and the safety limits on
delay, level and crossover apply to every profile regardless.

**Downloading a song in 8D.** The player's overflow menu offers *Download as 8D · MP3 320*, which
renders the song through the same engine and writes a **standalone file** — a second file for that song,
not a replacement for its ordinary download, which keeps playing exactly what it always did. It lands in
its own folder, carries `(8D)` in its tags and in the name it exports under, and copies into the phone's
`Music/Rizx` when "Save to the phone" is on, so it can be taken to a car stereo or another player. It is
a re-encode: 320 kbps is what loses the least *more* from an already-lossy source, and no bitrate turns a
compressed stream into a better one. A song already listened to with the effect on is rendered with the
profile measured from the recording; one that has not is rendered from its genre, since there is no way
to measure a song without playing it.

**When it stands down.** The effect stays enabled but reports itself as waiting, with the reason
visible in the menu, when the audio is going to the phone's speaker (the interaural cues it is built
from only exist when each ear gets its own channel), when Android is already applying spatial audio of
its own (API 32+, and that guard is itself a setting), or when the track is mono or in a PCM format it
cannot process. It never listens, never records, and adds nothing to the APK's dependencies.

## Recommendations

- An **on-device listening log** (plays, completions, skips, listening time, time-of-day) feeds taste
  clusters and **three daily mixes** at a 70/30 familiar/discovery split.
- Optional **regional charts** personalization inferred from SIM/locale — asked in-app, no OS permission.
- Everything is computed locally; nothing about your listening leaves the device.

## Library

Tabbed library: **All**, **Playlists**, **Liked**, **Recent**, **Downloads**, and **Local**, each with an
in-list filter bar (the visible, filtered list is exactly what plays).

- **Liked / favorites** — heart any track from anywhere; it round-trips through persistence and plays back
  from Liked.
- **User playlists** — create, edit, reorder; imported playlists become normal editable playlists.
- **Recently played** — recorded provider-agnostically as tracks play.

## Local music player

- **Scans the device library** (`MediaStore.Audio`) into Songs / Albums / Artists views, with sort options,
  an A–Z fast-scroll rail, per-row actions, and honest codec badges (FLAC/ALAC/… as claimed by the file).
- A **Files explorer** (Storage Access Framework) plays audio from any folder you pick — including places
  the media scan can't see — with **no permission at all**; local-only playlists round it out.
- Plays through the **same** Media3 pipeline as remote content (via `content://` URIs), so favorites,
  playlists, queue, and Recently Played all work for local files too.
- Permission: **`READ_MEDIA_AUDIO`** on Android 13+, the legacy `READ_EXTERNAL_STORAGE` below — requested
  contextually when you open the scan views; denial is non-fatal (Files and playlists keep working).

## Downloads & offline

- **Four download formats**, chosen in Settings or per-song from the player's ⋮ menu:
  - **Original** — the bytes as delivered (YouTube audio is M4A).
  - **Opus** — the best YouTube source repackaged losslessly from WebM into a real `.opus` (Ogg) file
    (Android 10+; the option doesn't exist below).
  - **MP3 320** — encoded on-device with a pure-Java LAME port (Android ships no MP3 encoder).
  - **FLAC** — true lossless when the community lossless source has the song (see Plugins).
- **Full embedded tags in every format** — cover art, artist, album, year: ID3v2+APIC for MP3, Vorbis
  comments + `METADATA_BLOCK_PICTURE` for Opus, a real PICTURE block for FLAC, `ilst` atoms for M4A. A
  downloaded file looks right in any player on any device.
- **Segmented, multi-connection downloading** for speed; a foreground service keeps a batch alive with an
  aggregate progress notification.
- **Save to the phone** — optionally publish each download into the shared `Music/Rizx` folder
  (MediaStore), visible to file managers and every other player. Asked once when your first download
  starts; per-row and bulk “save to phone” actions cover the backlog; deleting inside Rizx keeps the
  phone copy. On Android 8–9 this needs the legacy write permission, requested right at opt-in.
- Downloaded files are resolved from local storage **before** any network attempt — downloads are the
  offline library.

## Playlist import

- **By URL** — Spotify, YouTube / YouTube Music, Deezer and Apple Music playlist links.
- **By file** — Nuclear-JSON exports and Exportify CSV files.
- **By share link or QR** — a Rizx share link opened on a phone with Rizx installed opens the app and
  imports the playlist straight away. Without the app, the link shows a page with an "Open in Rizx"
  button and a download link.
- **Complete imports, whatever the length.** Deezer, Spotify and YouTube all page to the end; Apple
  Music's playlist page already carries its whole tracklist. Spotify used to arrive as its first 100
  tracks — the most its public embed will ship — and now pages past that with the anonymous bearer the
  same embed publishes. Playlist covers come along, and missing per-track art is backfilled from Deezer.
- **A short import says it is short.** When a source really does cut a list off, the playlist carries
  that notice on its own screen instead of passing a partial import off as a complete one.
- Imports are persisted and become normal, editable playlists. All import paths are **keyless** — Spotify
  is read from public embed data, never a private API secret.

## Account & sync

Optional. Rizx works fully without an account; signing in with Google keeps your library the same on
every device, the way a streaming service does:

- **Playlists, favorites and listening taste** travel with the account. Every edit is journaled
  locally and uploaded on its own within seconds; opening the app, or bringing it back after a while,
  pulls what other devices did. A backstop run every few hours catches a phone that sat in a drawer.
- **Other devices catch up in seconds.** While the app is on screen it listens on a private channel;
  when another device saves a playlist or likes a song, this one pulls the change within seconds. In
  the background the schedule above takes over — nothing depends on the channel being there.
- **Taste adds up across devices.** Each phone publishes its own listening counters; the app sums
  them, so a song played five times here and three times on the tablet is an eight-play song on both —
  and the personalized rows of the Home refresh when another device's history or likes arrive.
- **Local first, always.** Room stays the source of truth: nothing waits on the network, the app is
  identical offline, and signing out keeps everything on the phone. A device that last synced with a
  different account asks before uploading its library: merge it, take the cloud copy (recoverable for
  30 days), or stay signed out.
- Never uploaded: downloads, local files, stream URLs, the queue, recognition history, plugins or any
  provider credential. Data saver pauses only the listening-counter uploads.

## Home screen widgets

Two widgets in the look of a Nothing OS card, set in Rizx's own type and colours (Paper by day, Ivory
by night, the red state marker):

- **Now playing (4×2):** cover, title in the dot-matrix face, artist and clock, a progress bar made of
  little squares, previous / play / next, ♥, and a microphone that opens song identification.
- **Now playing · compact (4×1):** cover, title and artist, previous / play / next, ♥.
- **Audio ID (2×2):** a microphone that identifies what is playing around you; the last match stays
  on the small card — cover, title, artist · album — with a play button.
- The card's dotted bar is a **red seek bar**: tap anywhere along it to jump forward or back.
- They show the last song even with the app closed, and every button works from there: play resumes
  the restored queue, ♥ likes the current song, the microphone lands on Audio ID already listening.

## Artist pages

- **Full paged discography** split by type (albums / singles & EPs / compilations), top tracks, similar
  artists, and a **Wikipedia bio** (validated against the live API so the wrong article never shows).
- Artist names are resolved to a **canonical profile** (follower-ranked among same-name candidates), so
  opening an artist from a YouTube-sourced song lands on the real catalogue page.

## Plugins

- A **sandboxed QuickJS runtime** can download and run real Nuclear plugins.
- The sandbox exposes only `fetch` (no DOM, no filesystem, no Android APIs), with per-call timeouts and
  per-plugin crash isolation — a misbehaving plugin can't take down the app.
- The store lists **what you can still install**: entries Rizx already does natively (YouTube, SoundCloud,
  the Deezer dashboard, YouTube playlist import, multi-source search, media-session control) are hidden,
  as is anything that could not run here at all — a scrobbling plugin has no host to scrobble to. An
  installed plugin leaves the store for the **Installed** tab, and comes back if you remove it.
- A native **Plugins** screen shows each plugin's version, health, and an enable/disable toggle.
- The **community lossless (FLAC) source** is itself a plugin: the app ships as a generic plugin host and
  a fresh clone builds with **zero** bundled plugins (and no plugin section) by design. A build that does
  carry one installs it on first launch rather than asking you to find it in the store.

## Settings

- Every option is functional and persisted (DataStore-backed): playback (crossfade/gapless,
  normalization, adaptive quality, Hi-Res), download format & save-to-phone, canvas policies, radio
  algorithm, feed source, lyrics provider, cache size, and more.
- **Data saver** — a Rizx-level switch (plus automatic metered-network detection, hotspots included) that
  drops cover sizes and stream quality.
- **App language** — System / English / Español / Português / Français; the whole UI is localized in all
  four.
- **Theme mode** — System / Light / Dark (System follows the device live).

## Design & theming

- **Material 3** with a custom brutalist / Nothing-OS-inspired visual language (dot-matrix numerals,
  monospace labels, tactile press feedback and semantic haptics).
- Warm **Paper (light)** and near-black **Ivory (dark)** themes — driven entirely by design tokens
  (`RizxTheme.colors`), with no hard-coded colors in screens.
- **Responsive** — phones stay portrait; tablets and unfolded foldables get landscape and a two-pane
  Now Playing.
- Real cover art everywhere via Coil, with tinted procedural fallbacks when art is missing.

## Device compatibility

Rizx runs on **Android 8.0+ (API 26)**. The app was built API-first against modern Android, and every
newer-API nicety degrades gracefully behind a version check:

| Capability | Needs | On older devices |
|---|---|---|
| Music recognition | a microphone | The Audio ID screen says the device has no compatible input |
| Opus download format | Android 10 (API 29) | Option hidden; downloads keep their original container |
| Save-to-phone without a permission | Android 10 (API 29) | Android 8–9 ask the legacy write permission at opt-in |
| System output-switcher panel | Android 10 (API 29) | Bluetooth settings open directly |
| Genre read from local files (AutoEQ hint) | Android 11 (API 30) | Genre skipped; AutoEQ measures the audio instead |
| System splash screen, blur halo | Android 12 (API 31) | Plain launch field; no halo |
| OS-owned per-app language | Android 13 (API 33) | The in-app selector applies and persists it itself |
| Rich haptic semantics | Android 13–14 | Nearest classic haptic constants |

## Content sources

All streaming and metadata come from **keyless** sources: Deezer, Audius, iTunes/Apple (search, RSS
editorial and charts), YouTube and SoundCloud (via NewPipeExtractor), LRCLIB / NetEase / KuGou /
Musixmatch for lyrics, Wikipedia for artist bios, and a community FLAC index via plugin. No API keys or
secrets ship in the app — and access controls are respected: Spotify *search* is deliberately absent
(its token gate is an anti-bot control, not public data), while Spotify *playlists* stay importable
through the public embed. See [PROVIDERS.md](PROVIDERS.md) for exactly what each source provides and how.
