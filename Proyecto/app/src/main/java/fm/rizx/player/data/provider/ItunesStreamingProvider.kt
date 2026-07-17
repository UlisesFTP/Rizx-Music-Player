package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.ItunesIds
import fm.rizx.player.data.remote.itunes.toStream
import fm.rizx.player.data.remote.itunes.toStreamCandidateOrNull
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.StreamingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * First **real** streaming provider — iTunes 30-second previews. Two-phase resolution over the same
 * public API (NUCLEAR_UPSTREAM_STUDY.md §5): [searchForTrack] discovers candidates by "artist title"
 * text search (usually several imperfect matches); [getStreamUrl] resolves one candidate to its
 * ephemeral preview URL just before playback via an id lookup. Resolved URLs are never persisted.
 *
 * Works for tracks from **any** metadata provider (it matches by title/artist), not just iTunes ones.
 */
class ItunesStreamingProvider(
    private val api: ItunesApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StreamingProvider {

    override val id: String = ItunesIds.STREAMING
    override val kind: ProviderKind = ProviderKind.STREAMING
    override val name: String = "iTunes Preview"

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        val term = listOfNotNull(track.artists.firstOrNull()?.name, track.title)
            .joinToString(" ").trim()
        if (term.isEmpty()) return emptyList()
        return try {
            withContext(io) {
                api.search(term = term, limit = CANDIDATE_LIMIT).results.mapNotNull { it.toStreamCandidateOrNull() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "candidate search failed", e)
        }
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream {
        return try {
            withContext(io) {
                val dto = api.lookup(id = candidate.id).results.firstOrNull()
                    ?: throw AppError.ProviderFailure(name, "no result for candidate ${candidate.id}")
                dto.toStream(candidate.id)
                    ?: throw AppError.ProviderFailure(name, "no preview for candidate ${candidate.id}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            throw e
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "stream resolution failed", e)
        }
    }

    companion object {
        private const val CANDIDATE_LIMIT = 5
    }
}
