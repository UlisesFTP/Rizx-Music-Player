package fm.rizx.player.data.canvas

import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toCanvasCandidateOrNull
import fm.rizx.player.data.remote.youtube.toStreamCandidateOrNull
import fm.rizx.player.data.remote.youtube.toYoutubeCandidateOrNull
import fm.rizx.player.data.remote.youtube.youtubeWatchUrl
import fm.rizx.player.domain.canvas.CanvasMatchTarget
import fm.rizx.player.domain.canvas.CanvasStaticFilter
import fm.rizx.player.domain.canvas.CanvasSuitability
import fm.rizx.player.domain.canvas.CanvasTrackMatcher
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.CanvasProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds a short looping video to play behind the artwork — the "canvas" — from YouTube.
 *
 * **This is not a Spotify Canvas and cannot be.** A Canvas is a purpose-made 3-8s loop the artist
 * uploads, and Spotify's endpoint for them is neither public nor keyless, so using it would break this
 * project's no-embedded-secrets rule. Deezer has no equivalent at all — its public API returns only
 * images (`cover_*`, `picture_*`) on tracks, albums and artists. So what is available here is the
 * YouTube upload of the song's actual film.
 *
 * **Two gates, asking different questions.** [CanvasTrackMatcher] asks whether the upload is this
 * recording; [CanvasStaticFilter] asks whether anything in it moves. The second is why this provider
 * has its current shape: most of YouTube Music's catalogue is auto-generated `- Topic` uploads whose
 * whole video is the square cover art, and the first version resolved them correctly, played them
 * correctly, and showed a motionless frame.
 *
 * No caching here: the repository owns the cache, because a TTL and a URL expiry belong next to the
 * policy that decides whether to go out at all, not inside one of several providers.
 */
class YoutubeCanvasProvider(
    private val client: YoutubeExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CanvasProvider {

    override val id = "youtube"
    override val displayName = "YouTube"

    /** Last resort by design: a full music video is the fallback the feature brief warns about. */
    override val priority = 100

    override suspend fun resolve(
        track: Track,
        preferredAspect: CanvasAspect,
        quality: CanvasQuality,
    ): List<CanvasCandidate> = withContext(io) {
        // Walk the ranked list rather than betting everything on the top one. The last gate is the only
        // one that has seen the actual file, and it fires *after* an extraction — an artist's own
        // upload can be a perfect title/artist/duration match and still be the square art track. On
        // "Ella Baila Sola" that is exactly what the top hit is; abandoning there was the difference
        // between a canvas and none.
        for (match in matchFor(track).take(MAX_EXTRACTIONS)) {
            val candidate = client.streamInfo(match.watchUrl)
                .toCanvasCandidateOrNull(id, quality.maxHeight, match.score)
                ?: continue
            // A square frame from YouTube is cover art on a timeline, not a music video. Measured at
            // 360×360 on device.
            if (CanvasStaticFilter.isStillFrame(candidate.aspect)) continue
            return@withContext listOf(candidate)
        }
        emptyList()
    }

    /** The video to show, and how sure the matcher is that it belongs to this song. */
    private data class Match(val watchUrl: String, val score: Int)

    /** A search hit and the channel that uploaded it — the two things both gates need. */
    private data class Hit(val candidate: StreamCandidate, val uploader: String?)

    /**
     * A track imported from YouTube names its own video — but it does **not** get to skip the gates.
     *
     * The first version accepted it unconditionally, on the grounds that the user picked that upload.
     * They picked it to *listen* to, and this app's YouTube Music search returns topic uploads, so "the
     * track's own video" is very often precisely the still image the feature is trying to stop showing.
     * It still skips the *search* — there is nothing to match, the identity is exact — but its title
     * goes through [CanvasStaticFilter] like everyone else's.
     *
     * Everything else is found by a **plain video search**, deliberately *not* the streaming provider's
     * YouTube Music "songs" search, for the same reason: that one returns the topic uploads.
     */
    private fun matchFor(track: Track): List<Match> {
        val artist = track.artists.firstOrNull()?.name
        track.toYoutubeCandidateOrNull()?.let { own ->
            if (CanvasStaticFilter.rate(own.title, artist, artist) == CanvasSuitability.REJECTED) {
                return emptyList()
            }
            return listOf(Match(own.source.url ?: youtubeWatchUrl(own.id), score = 100))
        }

        val term = listOfNotNull(artist, track.title).joinToString(" ").trim()
        if (term.isEmpty()) return emptyList()

        // The uploader travels alongside the candidate because StreamCandidate has no artist field — for
        // a music video the channel *is* the artist credit, decorated ("DualipaVEVO"), and both
        // ArtistNameMatching and CanvasStaticFilter read it back.
        val hits = client.searchVideos(term, CANDIDATES).mapNotNull { item ->
            item.toStreamCandidateOrNull()?.let { candidate -> Hit(candidate, item.uploaderName) }
        }

        val ranked = CanvasTrackMatcher
            .rankAll(track, hits) { CanvasMatchTarget(it.candidate.title, it.uploader, it.candidate.durationMs) }
            .map { scored -> Ranked(scored.value, scored.score, CanvasStaticFilter.rate(scored.value.candidate.title, scored.value.uploader, artist)) }
            .filter { it.tier != CanvasSuitability.REJECTED }
            // Tier first, score second: a video on the artist's own channel beats a stranger's higher
            // score, and a lyric card only wins when nothing that actually films the song passed.
            .sortedWith(compareByDescending<Ranked> { it.tier }.thenByDescending { it.score })

        val best = ranked.firstOrNull() ?: return emptyList()
        if (isCoinFlip(best, ranked.getOrNull(1))) return emptyList()
        return ranked.map { Match(it.hit.candidate.source.url ?: youtubeWatchUrl(it.hit.candidate.id), it.score) }
    }

    private data class Ranked(val hit: Hit, val score: Int, val tier: CanvasSuitability)

    /**
     * Whether the top two are too alike to choose between.
     *
     * A search that answers with two *different* videos of equal merit has offered a coin flip rather
     * than an identification, and the right move is to show the cover. Compared only **within the same
     * tier**: a video on the artist's own channel outranking a stranger's is a real reason to prefer
     * one, however close their scores.
     */
    private fun isCoinFlip(best: Ranked, runnerUp: Ranked?): Boolean {
        if (runnerUp == null) return false
        if (runnerUp.tier != best.tier) return false
        if (runnerUp.hit.candidate.id == best.hit.candidate.id) return false
        return CanvasTrackMatcher.tooCloseToCall(best.score, runnerUp.score)
    }

    private companion object {
        /**
         * How many search hits to sift.
         *
         * Twice the five the first version took: with the static filter in place, most of a plain
         * search's leading rows — the topic upload, the lyric video, the reaction — are now discarded,
         * and stopping at five would often mean discarding everything before reaching the film.
         */
        const val CANDIDATES = 10

        /**
         * How many of the ranked hits may be extracted before giving up.
         *
         * More than one because the still-frame veto only fires *after* an extraction, and fewer than
         * all of them because each is a 2-4 round-trip NewPipe call. Three covers the shape that
         * actually occurs — the art track on the artist's own channel, then the real video.
         */
        const val MAX_EXTRACTIONS = 3
    }
}
