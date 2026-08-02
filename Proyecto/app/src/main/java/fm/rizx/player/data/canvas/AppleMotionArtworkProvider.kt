package fm.rizx.player.data.canvas

import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.ItunesResultDto
import fm.rizx.player.domain.canvas.CanvasMatchTarget
import fm.rizx.player.domain.canvas.CanvasTrackMatcher
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.CanvasProvider
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Apple's **animated album artwork** — the closest thing to a real Spotify Canvas that can be had
 * keylessly, and the reason covers actually move.
 *
 * Apple ships motion artwork for a large slice of the modern catalogue: a short, silent, seamlessly
 * looping video of the cover, in a **square** and a **portrait** cut. The album page on
 * `music.apple.com` embeds both URLs in its server-rendered JSON, readable with an ordinary GET — no
 * token, no developer key, no DRM. Verified live against *After Hours*: a multi-variant HLS ladder from
 * 360×360 up to 1080×1080 at 30 fps, `CLOSED-CAPTIONS=NONE` and **no audio rendition at all**.
 *
 * That last part matters. The YouTube provider below it can only offer the *music video*, and for the
 * auto-generated "topic" uploads that make up most of the catalogue that video is a **still image** —
 * which is exactly why nothing appeared to animate before this provider existed. Apple's asset is
 * purpose-made to loop.
 *
 * Two requests: the keyless iTunes Search API to identify the album (reusing [ItunesApi], already wired
 * for the iTunes metadata provider), then the album page for the motion URLs. Both are cached by the
 * repository, so a track costs them once.
 */
class AppleMotionArtworkProvider(
    private val itunes: ItunesApi,
    private val client: OkHttpClient,
    private val storefront: () -> String = { "us" },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The album page to read. Injectable so tests can serve a fixture instead of reaching Apple. */
    private val albumUrl: (String, Long) -> String = { cc, id -> ALBUM_URL.format(cc, id) },
) : CanvasProvider {

    override val id = "apple"
    override val displayName = "Apple Music"

    /** Ahead of YouTube: a loop made to be a cover beats a music video found by search, every time. */
    override val priority = 10

    override suspend fun resolve(
        track: Track,
        preferredAspect: CanvasAspect,
        quality: CanvasQuality,
    ): List<CanvasCandidate> = withContext(io) {
        // Owner-first (ADR 0020): a track that already knows its Apple album needs no search at all.
        // One request instead of two, and no chance of landing on somebody else's release.
        ownedAlbumId(track)?.let { owned ->
            motionArtwork(owned)?.let { motion ->
                return@withContext candidates(motion, preferredAspect, track.title, track.artists.firstOrNull()?.name, track.durationMs, OWNED_SCORE)
            }
        }

        // Several albums, not one. Motion artwork belongs to the *album*, but iTunes ranks by *track* —
        // and its best track match for "Blinding Lights" is the **single**, which has no motion artwork
        // at all while "After Hours" has both cuts. Verified live: album 1488408555 → nothing,
        // 1499378108 → square + tall. Stopping at the top row was silently losing most of the catalogue.
        for ((collectionId, row, score) in candidateAlbums(track).take(MAX_ALBUM_LOOKUPS)) {
            val motion = motionArtwork(collectionId) ?: continue
            return@withContext candidates(motion, preferredAspect, row.trackName, row.artistName, row.trackTimeMillis, score)
        }
        emptyList()
    }

    /**
     * Both cuts as candidates, the one that fits the screen first.
     *
     * Two rather than one because they are the same loop in two shapes: if the portrait variant turns
     * out not to play, the square one is a better answer than the static cover, and it costs no request
     * — the album page handed over both URLs at once.
     */
    private fun candidates(
        motion: Motion,
        preferredAspect: CanvasAspect,
        title: String?,
        artist: String?,
        durationMs: Long?,
        score: Int,
    ): List<CanvasCandidate> {
        val wantsTall = preferredAspect == CanvasAspect.PORTRAIT
        val ordered = if (wantsTall) listOfNotNull(motion.tall, motion.square) else listOfNotNull(motion.square, motion.tall)
        return ordered.distinct().map { url ->
            CanvasCandidate(
                providerId = id,
                mediaUrl = url,
                mimeType = HLS_MIME,
                aspect = if (url == motion.tall) CanvasAspect.PORTRAIT else CanvasAspect.SQUARE,
                title = title,
                artist = artist,
                durationMs = durationMs,
                score = score,
                // Apple's motion URLs carry no expiry token, unlike googlevideo's. The repository's own
                // TTL is the only thing that ages them out.
                expiresAtMs = null,
            )
        }
    }

    /**
     * The Apple album id the track already carries, if it has one.
     *
     * `ItunesMappers` stores it as `ProviderRef("itunes", "album:<collectionId>")`, and falls back to
     * `album:<name>` when the row had no id — hence the digits check rather than a blind substring.
     */
    private fun ownedAlbumId(track: Track): Long? {
        val source = track.album?.source ?: return null
        if (source.provider != ITUNES_PROVIDER) return null
        return source.id.removePrefix(ALBUM_PREFIX).takeIf { it != source.id }?.toLongOrNull()
    }

    /**
     * The albums worth asking about, best first: album id, the row it came from, and the match score.
     *
     * Ordered by **full album before single or EP**, then by score. A single is very often the better
     * *track* match — same title, same artist, same length — and almost never the one carrying motion
     * artwork, so leading with it wastes the one lookup that mattered.
     */
    private suspend fun candidateAlbums(track: Track): List<Triple<Long, ItunesResultDto, Int>> {
        val artist = track.artists.firstOrNull()?.name
        // The de-channelled name, so a track credited to "TheCranberriesTV" still finds The Cranberries.
        val term = listOfNotNull(artist?.let { ArtistNameMatching.searchName(it) }, track.title)
            .joinToString(" ")
            .trim()
        if (term.isEmpty()) return emptyList()

        val rows = itunes.search(term = term, limit = CANDIDATES).results
            .filter { it.collectionId != null }

        return rows.mapNotNull { row ->
            val score = CanvasTrackMatcher.score(
                track,
                CanvasMatchTarget(row.trackName.orEmpty(), row.artistName, row.trackTimeMillis),
            ) ?: return@mapNotNull null
            val corroborated = CanvasTrackMatcher.sameRecording(track, row.trackName.orEmpty(), row.artistName)
            if (!CanvasTrackMatcher.accepts(score, corroborated)) return@mapNotNull null
            Triple(row.collectionId!!, row, score)
        }
            .distinctBy { it.first }
            .sortedWith(compareBy({ if (isStandaloneRelease(it.second.collectionName)) 1 else 0 }, { -it.third }))
    }

    /** `"After Hours"` vs `"Blinding Lights - Single"` / `"… - EP"`, as Apple names them. */
    private fun isStandaloneRelease(collectionName: String?): Boolean {
        val name = collectionName?.trim()?.lowercase() ?: return false
        return name.endsWith("- single") || name.endsWith("- ep")
    }

    /** Both cuts of the album's motion artwork, or null when this album simply doesn't have one. */
    private fun motionArtwork(collectionId: Long): Motion? {
        val html = get(albumUrl(storefront(), collectionId))
        // Server-rendered JSON with `/` escaped as /; unescape once, then walk to each key. An
        // index walk rather than one big regex: the gap between the marker and its `video` field holds a
        // previewFrame object of unbounded size, and a length-capped or newline-sensitive pattern would
        // fail silently the day Apple pads it. A parse that finds nothing must mean "no canvas".
        val flat = html.replace("\\u002F", "/").replace("\\/", "/")
        return Motion(square = videoAfter(flat, SQUARE_KEY), tall = videoAfter(flat, TALL_KEY))
            .takeIf { it.square != null || it.tall != null }
    }

    /** The first `"video":"…m3u8"` following [marker], or null. */
    private fun videoAfter(json: String, marker: String): String? {
        val start = json.indexOf(marker)
        if (start < 0) return null
        val at = json.indexOf(VIDEO_KEY, start)
        if (at < 0) return null
        val open = json.indexOf('"', at + VIDEO_KEY.length)
        if (open < 0) return null
        val close = json.indexOf('"', open + 1)
        if (close < 0) return null
        return json.substring(open + 1, close).takeIf { it.endsWith(".m3u8") }
    }

    private data class Motion(val square: String?, val tall: String?)

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            // The album page is server-rendered only for a browser-ish client; the default OkHttp agent
            // gets a stub without the artwork JSON.
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("empty body")
        }
    }

    private companion object {
        const val CANDIDATES = 8

        /** The provider id `ItunesMappers` stamps on refs, and how it namespaces an album. */
        const val ITUNES_PROVIDER = "itunes"
        const val ALBUM_PREFIX = "album:"

        /** The track told us its own album; there is nothing left to be unsure about. */
        const val OWNED_SCORE = 100

        /**
         * How many album pages to try before giving up. Three covers the usual shape — single, studio
         * album, deluxe — without turning a track with no motion artwork into eight round trips. The
         * repository caches the outcome either way.
         */
        const val MAX_ALBUM_LOOKUPS = 3

        const val HLS_MIME = "application/x-mpegURL"
        const val ALBUM_URL = "https://music.apple.com/%s/album/%d"
        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * `"motionDetailSquare":{ …previewFrame… ,"video":"https://…m3u8"}` — `video` is the first such
         * key after the marker, in both cuts.
         */
        const val SQUARE_KEY = "\"motionDetailSquare\""
        const val TALL_KEY = "\"motionDetailTall\""
        const val VIDEO_KEY = "\"video\""
    }
}
