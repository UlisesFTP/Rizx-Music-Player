package fm.rizx.player.data.canvas

import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toCanvasVideoUrlOrNull
import fm.rizx.player.data.remote.youtube.toStreamCandidateOrNull
import fm.rizx.player.data.remote.youtube.toYoutubeCandidateOrNull
import fm.rizx.player.data.remote.youtube.youtubeWatchUrl
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a short looping video to play behind the Now Playing artwork — the "canvas".
 *
 * **This is not a Spotify Canvas and cannot be.** A Canvas is a purpose-made 3-8s loop the artist
 * uploads, and Spotify's endpoint for them is neither public nor keyless, so using it would break this
 * project's no-embedded-secrets rule. Deezer has no equivalent at all — its public API returns only
 * images (`cover_*`, `picture_*`) on tracks, albums and artists.
 *
 * What is actually available is the YouTube upload the audio is *already* being pulled from. For a track
 * with a real music video that looks great. For an auto-generated "topic" upload — which is most of
 * YouTube Music's catalogue — the video is a **still image**, and the canvas will correctly show a
 * motionless frame. The caller decides whether that trade is worth the bytes.
 */
interface CanvasSource {
    /** A low-res, progressive video URL for [track], or null when there is nothing to show. */
    suspend fun videoUrlFor(track: Track): String?
}

/**
 * Resolves canvases from YouTube, reusing the extractor the streaming provider already depends on.
 *
 * Results are cached by `ProviderRef.identityKey` for the process lifetime: re-opening Now Playing on
 * the same song, or toggling the canvas off and on, must not re-run a 2-4 round-trip extraction. The
 * URL itself is ephemeral (a googlevideo token) and is **never persisted** — this cache is memory-only,
 * exactly like the resolver's.
 */
class YoutubeCanvasSource(
    private val client: YoutubeExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CanvasSource {

    /** identityKey → video URL, or [NONE] for "asked already, there isn't one". */
    private val cache = ConcurrentHashMap<String, String>()

    override suspend fun videoUrlFor(track: Track): String? {
        val key = track.source.identityKey
        cache[key]?.let { return it.takeIf { url -> url != NONE } }
        return withContext(io) {
            val url = try {
                resolve(track)
            } catch (e: CancellationException) {
                throw e // leaving the screen must cancel cleanly, not cache a phantom miss
            } catch (e: Exception) {
                null // a broken extraction must never touch playback (ADR 0006)
            }
            // A miss caches as NONE too: a canvas is decoration, and retrying a dead extraction every
            // time the track re-emits would cost more than it could ever be worth.
            cache[key] = url ?: NONE
            url
        }
    }

    private fun resolve(track: Track): String? {
        val watchUrl = watchUrlFor(track) ?: return null
        return client.streamInfo(watchUrl).toCanvasVideoUrlOrNull()
    }

    /**
     * The video to show.
     *
     * A track imported from YouTube names its own video and keeps it — the user picked that upload.
     * Everything else is matched by artist/title through a **plain video search**, deliberately *not*
     * the streaming provider's YouTube Music "songs" search: that one returns auto-generated topic
     * uploads whose video is the square cover art, motionless. Verified on device — the audio path
     * resolved `music.youtube.com/watch?v=…` and the canvas came back `360x360` and perfectly still.
     * A plain search finds the real music video, which is the entire point of the feature.
     */
    private fun watchUrlFor(track: Track): String? {
        track.toYoutubeCandidateOrNull()?.let { return it.source.url ?: youtubeWatchUrl(it.id) }
        val term = listOfNotNull(track.artists.firstOrNull()?.name, track.title).joinToString(" ").trim()
        if (term.isEmpty()) return null
        // Several, not one: a plain search leads with whatever YouTube feels like — channels, mixes, a
        // live stream — and [toStreamCandidateOrNull] rejects anything that isn't a real video with a
        // duration. Taking only the first result throws the canvas away whenever the top hit isn't one.
        val match = client.searchVideos(term, CANDIDATES)
            .firstNotNullOfOrNull { it.toStreamCandidateOrNull() }
            ?: return null
        return match.source.url ?: youtubeWatchUrl(match.id)
    }

    private companion object {
        /** Sentinel: a real URL can never be this, and null can't live in a ConcurrentHashMap. */
        const val NONE = ""

        /** How many search hits to sift for a usable video before giving up. */
        const val CANDIDATES = 5
    }
}
