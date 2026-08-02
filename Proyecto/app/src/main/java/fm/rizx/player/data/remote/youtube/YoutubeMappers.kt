package fm.rizx.player.data.remote.youtube

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
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

/**
 * The biggest thumbnail the extractor reported. NewPipe doesn't guarantee the list's order, so taking
 * the last one could hand back a 68px placeholder; prefer the tallest with a known height and only fall
 * back to positional order when none report one.
 */
internal fun List<Image>.bestThumbnailUrl(): String? =
    filter { it.height > 0 }.maxByOrNull { it.height }?.url ?: lastOrNull()?.url

/**
 * The extractor's whole thumbnail ladder as an [ArtworkSet], sizes included.
 *
 * It used to collapse to a single `Artwork(url = best)` with **no width or height**, which cost twice
 * over: `pick()` scores an unsized variant as an infinite upscale and buries it under a -1000 penalty,
 * and with one rung there was nothing for Data saver to step down to. Carrying the real sizes means the
 * default ask (the largest) genuinely gets `maxresdefault` when the video has one, and the thrifty ask
 * gets a small one.
 *
 * Only URLs the extractor actually reported. Rewriting `hqdefault` into `maxresdefault` by hand would
 * be guessing at a file that exists for most music videos and 404s for the rest — a blank cover is a
 * worse outcome than a soft one.
 */
internal fun List<Image>.toArtworkSet(): ArtworkSet? {
    val sized = filter { it.url.isNotBlank() }
    if (sized.isEmpty()) return null
    val tallest = sized.filter { it.height > 0 }.maxOfOrNull { it.height } ?: 0
    return ArtworkSet(
        sized.map { image ->
            val px = image.height.takeIf { it > 0 }
            Artwork(
                url = image.url,
                width = image.width.takeIf { it > 0 } ?: px,
                height = px,
                // The biggest is the cover; the rest are the cheap rungs. A ladder whose every rung
                // claimed to be a COVER would let a 120px placeholder win a small target and show up
                // blurry on a card that had a better option available.
                purpose = if (px != null && px == tallest) ArtworkPurpose.COVER else ArtworkPurpose.THUMBNAIL,
            )
        },
    )
}

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
        thumbnail = thumbnails.bestThumbnailUrl(),
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
        // A video still, not cover art — callers that can reach a metadata provider should upgrade it
        // (see TrackArtworkEnricher's `upgradeFrom`); this is the offline-safe fallback.
        artwork = thumbnails.toArtworkSet(),
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
        artwork = thumbnails.toArtworkSet(),
        source = YoutubeIds.playlist(listId),
        trackCount = streamCount.takeIf { it >= 0 }?.toInt(),
    )
}

/**
 * Phase-2: pick an **audio-only** stream and wrap it as an ephemeral [Stream]. Prefers progressive HTTP
 * (ExoPlayer plays it directly) and the **original** audio track over dubbed/auto-translated ones.
 *
 * [maxQuality] (the Hi-Res setting) switches the pick from "M4A first" to *actual* perceptual quality —
 * see [effectiveBitrate]. That is what finally lets YouTube's Opus track win over its AAC one, and it
 * comes with a second, free win: Opus is always 48 kHz, the rate Android's mixer runs at, so the
 * platform never has to resample it (Google's own NDK guidance: resamplers add passband ripple and
 * aliasing, "avoid using them unnecessarily").
 *
 * When [preferLow] is set (the user's data saver) it drops to the lowest *adequate* stream instead.
 * Returns null when the video exposes no audio stream.
 */
fun StreamInfo.toBestAudioStreamOrNull(
    candidate: StreamCandidate,
    preferLow: Boolean = false,
    maxQuality: Boolean = false,
): Stream? {
    val all = audioStreams.orEmpty()
    if (all.isEmpty()) return null
    val progressive = all.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.ifEmpty { all }
    val usable = progressive.originalTrackOrAll()
    val best = (if (preferLow) usable.lowestAdequateOrNull() else null)
        ?: usable.maxWithOrNull(qualityOrder(maxQuality))
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
 * A canvas candidate built from the **muxed** progressive video streams — or null if the video exposes
 * none. In practice YouTube serves exactly one of these, itag 18 at 360p.
 *
 * Small, not best: this plays behind the artwork under a scrim, on top of the audio stream the same
 * song is already pulling. A 1080p background would multiply the data cost of listening for something
 * nobody looks at closely. [maxHeight] is the cap the network policy asked for; when every stream is
 * bigger than that, the smallest one is used anyway — a too-large canvas beats none.
 *
 * The runner-up becomes [CanvasCandidate.fallbackUrl], so a stream that turns out not to play costs one
 * retry instead of the whole feature.
 *
 * **Not [videoOnlyStreams], despite appearances.** That ladder goes down to 144p and NewPipe labels it
 * `PROGRESSIVE_HTTP`, which looks like a free win — less data, no audio track to throw away. It isn't:
 * googlevideo throttles those URLs to a trickle unless the client asks for byte ranges the way yt-dlp
 * does, and ExoPlayer's plain GET simply times out (`SocketTimeoutException` in `DefaultHttpDataSource`).
 * Verified on device. The muxed stream is a real progressive file and plays; its wasted audio track is
 * the price — the canvas player mutes it and switches its renderer off.
 */
fun StreamInfo.toCanvasCandidateOrNull(
    providerId: String,
    maxHeight: Int,
    score: Int,
): CanvasCandidate? {
    val usable = videoStreams.orEmpty()
        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content != null }
        .sortedBy { it.resolutionHeight() }
    if (usable.isEmpty()) return null
    // Largest that still fits the cap, so an unmetered connection can have the 480p when there is one;
    // if nothing fits, the smallest available — the cap is a budget, not a requirement.
    val chosen = usable.lastOrNull { it.resolutionHeight() <= maxHeight } ?: usable.first()
    val fallback = usable.firstOrNull { it !== chosen }
    val url = chosen.content ?: return null
    return CanvasCandidate(
        providerId = providerId,
        mediaUrl = url,
        fallbackUrl = fallback?.content,
        mimeType = chosen.format?.mimeType,
        aspect = chosen.canvasAspect(),
        title = name,
        artist = uploaderName,
        durationMs = duration.takeIf { it > 0 }?.times(1_000L),
        score = score,
        expiresAtMs = googlevideoExpiryMs(url),
        width = chosen.width.takeIf { it > 0 },
        height = chosen.height.takeIf { it > 0 },
    )
}

/**
 * When the signed URL stops working, from googlevideo's own `expire=<unix seconds>` parameter.
 *
 * Worth parsing rather than guessing: it is the difference between a cache that quietly starts handing
 * out dead links after a few hours and one that knows to go back for a fresh token. Null when the URL
 * carries no expiry or an unparseable one — the caller then falls back to its own TTL.
 */
internal fun googlevideoExpiryMs(url: String): Long? =
    EXPIRE_PARAM.find(url)?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.times(1_000L)

private val EXPIRE_PARAM = Regex("""[?&]expire=(\d+)""")

/**
 * A YouTube video is 16:9 unless it's a Short, in which case NewPipe reports a taller frame. Derived
 * from the stream's own dimensions rather than assumed, so a vertical upload is labelled honestly —
 * and so `CanvasStaticFilter` can veto the square frame of an auto-generated cover-art upload.
 */
internal fun VideoStream.canvasAspect(): CanvasAspect {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return CanvasAspect.LANDSCAPE
    val ratio = w.toFloat() / h
    return when {
        ratio < 0.95f -> CanvasAspect.PORTRAIT
        ratio <= 1.05f -> CanvasAspect.SQUARE
        else -> CanvasAspect.LANDSCAPE
    }
}

/** `"360p"`/`"1080p60"` → 360/1080. Unknown parses sort last so a real resolution always wins. */
private fun VideoStream.resolutionHeight(): Int =
    resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: Int.MAX_VALUE

/**
 * How the "best" stream is chosen.
 *
 * [maxQuality] off keeps the historical rule — **M4A/AAC first**, then bitrate — because the M4A path is
 * the one downloads can tag and the one this app has always streamed.
 *
 * [maxQuality] on ranks by [effectiveBitrate] instead, so a 160 kbps Opus track beats a 128 kbps AAC one
 * the way it actually sounds. Ties break toward M4A so the choice stays deterministic.
 */
private fun qualityOrder(maxQuality: Boolean): Comparator<AudioStream> =
    if (maxQuality) {
        compareBy({ it.effectiveBitrate() }, { if (it.format == MediaFormat.M4A) 1 else 0 })
    } else {
        compareBy({ if (it.format == MediaFormat.M4A) 1 else 0 }, { it.averageBitrate.coerceAtLeast(0) })
    }

/**
 * Bitrate weighted by how efficient the codec is, so streams of different codecs are comparable at all.
 * Opus is worth roughly 1.6× AAC at these rates (it also carries a higher sample rate); MP3 needs far
 * more bits for the same result. Read from [MediaFormat.suffix] — **never** from `getItagItem()`, which
 * is YouTube-only and null for SoundCloud, which shares this picker.
 */
private fun AudioStream.effectiveBitrate(): Double =
    averageBitrate.coerceAtLeast(0) * when (format?.suffix?.lowercase()) {
        "opus" -> 1.6
        "m4a", "aac", "mp4" -> 1.0
        "webm", "ogg" -> 0.9 // Vorbis
        "mp3" -> 0.7
        else -> 1.0 // unknown codec: judge it on raw bitrate alone
    }

/**
 * The original audio only, when any candidate says which it is — otherwise YouTube's auto-dubbed track
 * can outrank the real one and the song plays in the wrong voice. Sources that don't report a track type
 * at all (SoundCloud, older extractions) fall through unfiltered.
 */
private fun List<AudioStream>.originalTrackOrAll(): List<AudioStream> {
    if (none { it.audioTrackType != null }) return this
    return filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { this }
}

/**
 * Data-saver pick: the lowest stream that is still *worth playing* — at least [DATA_SAVER_MIN_FRACTION]
 * of the best one on offer — rather than the absolute floor, which on YouTube is a ~50 kbps stream that
 * sounds broken. Expressed as a fraction of the best because NewPipe's bitrate unit varies by source, so
 * any absolute threshold would be meaningless for one of them. Null when nothing reports a bitrate.
 */
private fun List<AudioStream>.lowestAdequateOrNull(): AudioStream? {
    val known = filter { it.averageBitrate > 0 }
    if (known.isEmpty()) return null
    val ceiling = known.maxOf { it.effectiveBitrate() }
    val adequate = known.filter { it.effectiveBitrate() >= ceiling * DATA_SAVER_MIN_FRACTION }
    return adequate.ifEmpty { known }
        .minWithOrNull(compareBy({ it.averageBitrate }, { if (it.format == MediaFormat.M4A) 0 else 1 }))
}

/** Data saver won't go below this share of the best available quality. */
private const val DATA_SAVER_MIN_FRACTION = 0.35
