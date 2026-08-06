package fm.rizx.player.data.recognition

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.toTrackOrNull
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.RecognitionMatch
import fm.rizx.player.domain.recognition.RecognitionTrackResolver
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Finds the recognised recording inside this app's own catalogue, so it can be played by the ordinary
 * pipeline instead of by anything recognition-specific.
 *
 * **Three rungs, exact first.** The naive version of this feature — take the title and artist, search
 * a video site, play the first hit — is how a recognition turns into a lyric video, a slowed remix or
 * a cover. Both exact identifiers the service publishes are used before any text is searched, and the
 * text search is scored rather than trusted:
 *
 * 1. **ISRC → Deezer.** The recording's own identifier against an identity endpoint. No ranking is
 *    involved, so there is nothing to be wrong about.
 * 2. **Apple's `adamid` → iTunes.** Also exact, and this app already speaks to that endpoint. Verified
 *    against the recognition anyway, since an id can point at an edition rather than a recording.
 * 3. **Scored search.** [RecognitionMatcher] against the active catalogue, returning `null` when
 *    nothing is convincing.
 *
 * Returning `null` is a normal, supported outcome: the screen shows what was heard and offers to
 * search for it. That is a better answer than a confident wrong one.
 *
 * Each rung is independently failure-tolerant — a provider that is down loses its turn instead of
 * ending the resolution.
 */
internal class DefaultRecognitionTrackResolver(
    private val deezer: DeezerApi,
    private val itunes: ItunesApi,
    private val metadata: MetadataRepository,
    private val matcher: RecognitionMatcher,
    private val io: CoroutineDispatcher,
) : RecognitionTrackResolver {

    override suspend fun resolve(match: RecognitionMatch): Track? = withContext(io) {
        byIsrc(match) ?: byAppleId(match) ?: bySearch(match)
    }

    /**
     * The ISRC identifies the *recording*, so whatever comes back is by definition the right one and
     * is not second-guessed: re-checking it against the title would only let a catalogue's spelling
     * ("Get Lucky" vs "Get Lucky - Radio Edit") reject a certainty.
     */
    private suspend fun byIsrc(match: RecognitionMatch): Track? {
        val isrc = match.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return attempt { deezer.trackByIsrc(isrc).toTrackOrNull() }
    }

    private suspend fun byAppleId(match: RecognitionMatch): Track? {
        val adamId = match.appleTrackId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = attempt {
            itunes.lookup(id = adamId, entity = "song", limit = 1).results.firstOrNull()?.toTrackOrNull()
        } ?: return null
        return candidate.takeIf { matcher.accepts(match, it) }
    }

    private suspend fun bySearch(match: RecognitionMatch): Track? {
        val artist = ArtistNameMatching.credits(match.artist).firstOrNull()?.let(ArtistNameMatching::searchName)
        val query = listOfNotNull(match.title, artist).joinToString(" ").trim()
        if (query.isEmpty()) return null

        // Title and lead artist only. Appending the album — which the service does supply — narrows
        // the query enough that catalogues start returning nothing at all, and the album is worth more
        // as a scoring signal on the results than as a filter on the request.
        val results = attempt {
            metadata.search(SearchParams(query = query, types = listOf(SearchCategory.TRACKS), limit = SEARCH_LIMIT))
        } ?: return null

        return matcher.best(match, results.tracks)
    }

    /** A rung that fails loses its turn; only cancellation propagates. */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LinkageError) {
        // A missing platform API inside a dependency is an Error, not an Exception, and would
        // otherwise sail past the catch below and take the process with it.
        null
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SEARCH_LIMIT = 10
    }
}
