package fm.rizx.player.data.remote.youtube

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

/** Registry id + constants for the native YouTube streaming provider (ADR 0014). */
object YoutubeIds {
    const val STREAMING = "youtube"

    /** Namespaced so a playlist ref can't collide with a video ref (same convention as `DeezerIds`). */
    fun playlist(id: String) = ProviderRef(STREAMING, "playlist:$id")
}

/**
 * The playlist id in [url], or null when it isn't a YouTube playlist link. Delegates to NewPipe's link
 * handler, which already accepts `youtube.com`, `www.`, `m.`, **`music.youtube.com`** and
 * `watch?v=…&list=…` — strictly more correct than a hand-rolled regex.
 */
fun youtubePlaylistId(url: String): String? =
    runCatching { YoutubePlaylistLinkHandlerFactory.getInstance().getId(url) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

/**
 * Mixes/radio (`RD…`, auto-generated and endless) and the private auto-lists — Liked Music (`LM`) and
 * Watch later (`WL`) — aren't importable: the former aren't real playlists, the latter need an account.
 */
fun isImportableYoutubePlaylistId(id: String): Boolean =
    !id.startsWith("RD") && id != "LM" && id != "WL"

/**
 * Pure DTO → domain mappers for YouTube (NewPipeExtractor). The candidate carries the 11-char video id
 * as identity and the watch URL in [ProviderRef.url] for just-in-time phase-2 resolution. The concrete
 * audio URL (a short-lived googlevideo host) is **ephemeral** — resolved just before playback, never
 * persisted (the resolver already strips it).
 */

private val VIDEO_ID = Regex("""(?:v=|vi=|youtu\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})""")
private val VIDEO_ID_ONLY = Regex("""^[A-Za-z0-9_-]{11}$""")

/** Extracts the 11-char YouTube video id from a watch/short/embed URL, or null. */
fun youtubeVideoId(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

/** True for a bare 11-char video id (so a namespaced ref like `playlist:…` can't be mistaken for one). */
fun isYoutubeVideoId(id: String): Boolean = VIDEO_ID_ONLY.matches(id)

/** The canonical watch URL for a video id. */
fun youtubeWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

/**
 * A phase-1 [StreamCandidate] built straight from a track that **already carries its YouTube video id**
 * (e.g. imported from a YouTube playlist): plays that exact video instead of re-searching by artist/title
 * — which could land on a different video — and saves the search round-trip. Null when not YouTube-identified.
 */
fun Track.toYoutubeCandidateOrNull(): StreamCandidate? {
    if (source.provider != YoutubeIds.STREAMING) return null
    val videoId = source.id.takeIf { isYoutubeVideoId(it) } ?: return null
    return StreamCandidate(
        id = videoId,
        title = title,
        durationMs = durationMs,
        source = ProviderRef(YoutubeIds.STREAMING, videoId, source.url ?: youtubeWatchUrl(videoId)),
    )
}

/** Phase-1 candidate. Drops rows with no id/title and lives/unknown-duration items (duration ≤ 0). */
fun StreamInfoItem.toStreamCandidateOrNull(): StreamCandidate? {
    val itemUrl = url ?: return null
    val videoId = youtubeVideoId(itemUrl) ?: return null
    val name = name ?: return null
    val durationSec = duration // seconds; 0 or negative for live / unknown
    if (durationSec <= 0) return null
    return StreamCandidate(
        id = videoId,
        title = name,
        durationMs = durationSec * 1000L,
        thumbnail = thumbnails.lastOrNull()?.url,
        source = ProviderRef(YoutubeIds.STREAMING, videoId, itemUrl),
    )
}

/**
 * Playlist import: a playlist row → a domain [Track] that keeps its **exact video id** as identity, so it
 * plays that video rather than being re-searched by artist/title. Drops rows with no id/title and
 * lives/unknown-duration items (duration ≤ 0), same as [toStreamCandidateOrNull]. Duration is seconds → ms.
 */
fun StreamInfoItem.toTrackOrNull(): Track? {
    val itemUrl = url ?: return null
    val videoId = youtubeVideoId(itemUrl) ?: return null
    val title = name?.takeIf { it.isNotBlank() } ?: return null
    val durationSec = duration
    if (durationSec <= 0) return null
    return Track(
        title = title,
        artists = listOfNotNull(uploaderName?.takeIf { it.isNotBlank() }?.let { ArtistCredit(name = it) }),
        durationMs = durationSec * 1000L,
        artwork = thumbnails.lastOrNull()?.url?.let { ArtworkSet(listOf(Artwork(url = it))) },
        source = ProviderRef(YoutubeIds.STREAMING, videoId, itemUrl),
    )
}

/**
 * A YouTube playlist search row → a [PlaylistRef] for the Search "Playlists" tab. Identity is the
 * playlist list id (kept namespaced as `playlist:<listId>`, matching import origins), so opening it
 * reconstructs `youtube.com/playlist?list=<listId>` and loads the exact playlist. Drops rows with no
 * importable list id or no title, and mixes/radio (`RD…`) and the private auto-lists.
 */
fun PlaylistInfoItem.toPlaylistRefOrNull(): PlaylistRef? {
    val listId = url?.let { youtubePlaylistId(it) }?.takeIf { isImportableYoutubePlaylistId(it) } ?: return null
    val n = name?.takeIf { it.isNotBlank() } ?: return null
    return PlaylistRef(
        id = listId,
        name = n,
        artwork = thumbnails.lastOrNull()?.url?.let { ArtworkSet(listOf(Artwork(url = it))) },
        source = YoutubeIds.playlist(listId),
        trackCount = streamCount.takeIf { it >= 0 }?.toInt(),
    )
}

/**
 * Phase-2: pick an **audio-only** stream and wrap it as an ephemeral [Stream]. Prefers progressive HTTP
 * (ExoPlayer plays it directly). By default picks the **highest** quality (M4A/AAC first, then bitrate);
 * when [preferLow] is set (data saver on cellular, or a weak signal) it picks the **lowest** bitrate
 * instead — a real reduction that's independent of NewPipe's bitrate unit. Returns null when the video
 * exposes no audio stream.
 */
fun StreamInfo.toBestAudioStreamOrNull(candidate: StreamCandidate, preferLow: Boolean = false): Stream? {
    val all = audioStreams.orEmpty()
    if (all.isEmpty()) return null
    val progressive = all.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.ifEmpty { all }
    val best = (if (preferLow) progressive.lowestAudioOrNull() else null)
        ?: progressive.maxByOrNull { it.audioRank() }
        ?: return null
    val url = best.content ?: return null
    return Stream(
        url = url,
        // Tag the transport honestly. YouTube audio is progressive HTTPS; SoundCloud (same mapper) can be
        // HLS-only, and an `.m3u8` mislabelled HTTPS would fail to play (the player's HLS retry keys on
        // this) and could be "downloaded" as a 2 KB manifest.
        protocol = if (best.deliveryMethod == DeliveryMethod.HLS) StreamProtocol.HLS else StreamProtocol.HTTPS,
        mimeType = best.format?.mimeType,
        bitrateKbps = best.averageBitrate.takeIf { it > 0 },
        codec = best.format?.name,
        container = best.format?.suffix,
        qualityLabel = if (preferLow) "Data saver" else "Full track",
        durationMs = candidate.durationMs,
        source = candidate.source,
    )
}

/**
 * The lowest-resolution **muxed** video stream's URL, for the Now Playing canvas — or null if the video
 * exposes none. In practice YouTube serves exactly one of these, itag 18 at 360p.
 *
 * Lowest, not best: this plays behind the artwork under a scrim, on top of the audio stream the same
 * song is already pulling. A 1080p background would multiply the data cost of listening for something
 * nobody looks at closely.
 *
 * **Not [videoOnlyStreams], despite appearances.** That ladder goes down to 144p and NewPipe labels it
 * `PROGRESSIVE_HTTP`, which looks like a free win — less data, no audio track to throw away. It isn't:
 * googlevideo throttles those URLs to a trickle unless the client asks for byte ranges the way yt-dlp
 * does, and ExoPlayer's plain GET simply times out (`SocketTimeoutException` in `DefaultHttpDataSource`).
 * Verified on device. The muxed stream is a real progressive file and plays; its wasted audio track is
 * the price. The canvas player mutes it.
 */
fun StreamInfo.toCanvasVideoUrlOrNull(): String? =
    videoStreams.orEmpty()
        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content != null }
        .minByOrNull { it.resolutionHeight() }
        ?.content

/** `"360p"`/`"1080p60"` → 360/1080. Unknown parses sort last so a real resolution always wins. */
private fun VideoStream.resolutionHeight(): Int =
    resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: Int.MAX_VALUE

/** M4A/AAC first (best ExoPlayer support), then higher average bitrate. */
private fun AudioStream.audioRank(): Int {
    val formatBonus = if (format == MediaFormat.M4A) 1_000_000 else 0
    return formatBonus + averageBitrate.coerceAtLeast(0)
}

/**
 * Data-saver pick: the lowest **known** average bitrate (tie-broken toward M4A for ExoPlayer support),
 * or null if no stream reports a bitrate (caller falls back to the max pick). Comparing raw bitrates is
 * unit-agnostic — "lowest is lowest" whether NewPipe reports kbps or bps.
 */
private fun List<AudioStream>.lowestAudioOrNull(): AudioStream? =
    filter { it.averageBitrate > 0 }
        .minWithOrNull(compareBy({ it.averageBitrate }, { if (it.format == MediaFormat.M4A) 0 else 1 }))
