package fm.rizx.player.data.canvas

import fm.rizx.player.domain.canvas.CanvasMatchTarget
import fm.rizx.player.domain.canvas.CanvasTrackMatcher
import fm.rizx.player.domain.match.RecordingIdentity
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.CanvasProvider
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * TIDAL's **animated album covers** — the second source of purpose-made motion artwork, between
 * Apple's and the YouTube music-video fallback.
 *
 * TIDAL attaches a `videoCover` id to some albums; search results carry it inline, both on a track's
 * `album` and on album rows. The id expands to a plain MP4 on `resources.tidal.com` — a short, silent,
 * square loop of the cover, like Apple's square cut. Discovery goes through TIDAL's search endpoint
 * using the **embed player's public token**: no login, no OAuth, no user key. Verified live
 * (2026-08-19): search 200, "Blinding Lights" → *After Hours* `videoCover`, CDN 200 `video/mp4`.
 *
 * That token is not a credential and not a stable public developer contract — treat the whole
 * mechanism as **best-effort**. If TIDAL changes or closes it, this provider starts throwing or
 * returning nothing, the registry falls through to YouTube, and playback never notices. That
 * isolation is the point of it being just another [CanvasProvider].
 *
 * Like the others: no cache here (the repository owns TTLs for hits, misses and errors separately),
 * nothing persisted, and transport problems **throw** — a 5xx must age out in 2 minutes as a provider
 * error, not sit for 20 as a false "this song has no canvas".
 */
class TidalCanvasProvider(
    private val client: OkHttpClient,
    private val countryCode: () -> String = ::defaultCountryCode,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable so tests can point the provider at a MockWebServer instead of TIDAL. */
    private val baseUrl: String = BASE_URL,
) : CanvasProvider {

    override val id = "tidal"
    override val displayName = "TIDAL"

    /** After Apple (whose ladder offers more cuts and sizes), before the searched music video. */
    override val priority = 20

    override suspend fun resolve(
        track: Track,
        preferredAspect: CanvasAspect,
        quality: CanvasQuality,
    ): List<CanvasCandidate> = withContext(io) {
        // preferredAspect is a hint and this mechanism only has one shape; quality doesn't change the
        // URL either — 1280x1280 is the only asset path verified against the real CDN, and inventing
        // sibling resolutions would 404 on some albums and silently mislead the cache on others.
        val fromTracks = trackSearch(track)
        if (fromTracks.isNotEmpty()) return@withContext fromTracks
        albumSearch(track)
    }

    /**
     * Path A: search TRACKS and read `album.videoCover` off the rows the matcher accepts.
     *
     * Never the first row unexamined: the same title comes back from the original album, remixes and
     * covers ("Blinding Lights" returns five different albums in the first five rows). The ranked
     * list that survives is returned whole, best first — every row is a free spare, and a spare is
     * what lets the player retry a dead MP4 without another resolve.
     */
    private fun trackSearch(track: Track): List<CanvasCandidate> {
        val artist = track.artists.firstOrNull()?.name
        // Deliberately WITHOUT the album name: TIDAL's search treats extra words as constraints, and
        // "INCÓMODO Tito Double P MR INTERNACIONAL" fails to return a track that "Tito Double P MR
        // INTERNACIONAL" finds at rank 1 (verified live). The album still gets its say through the
        // ALBUMS fallback below.
        val query = listOfNotNull(artist, track.title).joinToString(" ").trim()
        if (query.isEmpty()) return emptyList()
        val page = search(query, types = "TRACKS").tracks?.items.orEmpty()

        return CanvasTrackMatcher
            .rankAll(track, page) { row ->
                // TIDAL durations are in seconds (verified live: 213 for a 3:33 track). The artists
                // array is the billing; a lone `artist` object misses collaborations entirely.
                CanvasMatchTarget(row.title.orEmpty(), row.artistNames(), row.duration?.times(1000L))
            }
            .mapNotNull { scored ->
                val url = formatVideoUrl(scored.value.album?.videoCover.orEmpty()) ?: return@mapNotNull null
                candidate(url, scored.value.title, scored.value.artistNames(), scored.value.duration?.times(1000L), scored.score)
            }
            .distinctBy { it.mediaUrl }
    }

    /**
     * Path B: the cover belongs to the *album*, so when no track row carried it, ask for the album
     * itself. Gated conservatively with what the domain already knows how to ask:
     * [RecordingIdentity.sharesArtwork] is literally "would these two titles ship under the same
     * cover?" — it forgives a remaster, and rejects the deluxe, live, karaoke and tribute editions
     * that a title search happily returns. An album row without an artist is rejected outright; a
     * wrong animated cover is worse than none.
     */
    private fun albumSearch(track: Track): List<CanvasCandidate> {
        val albumTitle = track.album?.title?.takeIf { it.isNotBlank() } ?: return emptyList()
        val artist = track.artists.firstOrNull()?.name ?: return emptyList()
        val rows = search("$albumTitle $artist", types = "ALBUMS").albums?.items.orEmpty()

        return rows.mapNotNull { row ->
            val url = formatVideoUrl(row.videoCover.orEmpty()) ?: return@mapNotNull null
            val rowTitle = row.title.orEmpty()
            if (!RecordingIdentity.sharesArtwork(albumTitle, rowTitle)) return@mapNotNull null
            val rowArtist = row.artistNames() ?: return@mapNotNull null
            if (track.artists.none { ours -> ArtistNameMatching.sameArtist(ours.name, rowArtist) }) return@mapNotNull null
            candidate(url, rowTitle, rowArtist, durationMs = null, score = CanvasTrackMatcher.CORROBORATE)
        }.distinctBy { it.mediaUrl }
    }

    private fun candidate(url: String, title: String?, artist: String?, durationMs: Long?, score: Int) =
        CanvasCandidate(
            providerId = id,
            mediaUrl = url,
            mimeType = MIME,
            aspect = CanvasAspect.SQUARE,
            title = title,
            artist = artist,
            durationMs = durationMs,
            score = score,
            expiresAtMs = null, // plain CDN path, no token to expire
            width = SIZE,
            height = SIZE,
        )

    private fun search(query: String, types: String): TidalSearchDto {
        val url = (baseUrl.trimEnd('/') + "/search").toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", CANDIDATES.toString())
            .addQueryParameter("types", types)
            .addQueryParameter("countryCode", countryCode())
            .build()
        val request = Request.Builder().url(url).header("X-Tidal-Token", EMBED_TOKEN).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("empty body")
            // Malformed JSON throws out of here on purpose: a changed API is a provider error to age
            // out quickly, not a durable "no canvas" verdict on the song.
            return json.decodeFromString(TidalSearchDto.serializer(), body)
        }
    }

    // ---- DTOs — only the fields read, nothing else of TIDAL's API is modeled ----

    @Serializable
    private data class TidalSearchDto(val tracks: TidalPageDto? = null, val albums: TidalPageDto? = null)

    @Serializable
    private data class TidalPageDto(val items: List<TidalRowDto> = emptyList())

    /** One shape for both result kinds: a track row nests its album, an album row IS the album. */
    @Serializable
    private data class TidalRowDto(
        val title: String? = null,
        val duration: Long? = null,
        val artists: List<TidalArtistDto> = emptyList(),
        val artist: TidalArtistDto? = null,
        val album: TidalAlbumDto? = null,
        val videoCover: String? = null,
    ) {
        fun artistNames(): String? = artists.mapNotNull { it.name }.ifEmpty { listOfNotNull(artist?.name) }
            .joinToString(", ").takeIf { it.isNotBlank() }
    }

    @Serializable
    private data class TidalArtistDto(val name: String? = null)

    @Serializable
    private data class TidalAlbumDto(val title: String? = null, val videoCover: String? = null)

    companion object {
        /**
         * A `videoCover` id becomes a CDN path segment by segment. Exactly five non-empty segments or
         * nothing — if TIDAL ever changes the id's shape, refusing to fabricate a URL is what keeps
         * the failure a quiet miss instead of a broken player.
         */
        internal fun formatVideoUrl(videoCover: String): String? {
            val parts = videoCover.split("-")
            if (parts.size != 5 || parts.any { it.isBlank() }) return null
            return "https://resources.tidal.com/videos/${parts.joinToString("/")}/${SIZE}x$SIZE.mp4"
        }

        /** `Locale` country when it is a real two-letter code; TIDAL wants *some* storefront. */
        internal fun defaultCountryCode(): String =
            Locale.getDefault().country.uppercase(Locale.ROOT)
                .takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: "US"

        private val json = Json { ignoreUnknownKeys = true }

        private const val BASE_URL = "https://api.tidal.com/v1/"

        /**
         * The TIDAL **embed player's** token, shipped to every visitor of an embedded TIDAL widget —
         * public by design, not a user credential and not a secret of this project. It grants search,
         * nothing account-scoped.
         */
        private const val EMBED_TOKEN = "vNVdglQOjFJJGG2U"

        private const val CANDIDATES = 10
        private const val MIME = "video/mp4"

        /** The only asset size verified against the real CDN. */
        private const val SIZE = 1280
    }
}
