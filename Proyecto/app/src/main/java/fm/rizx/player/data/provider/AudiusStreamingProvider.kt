package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.audius.AudiusApi
import fm.rizx.player.data.remote.audius.AudiusHostProvider
import fm.rizx.player.data.remote.audius.AudiusIds
import fm.rizx.player.data.remote.audius.audiusStream
import fm.rizx.player.data.remote.audius.matches
import fm.rizx.player.data.remote.audius.toStreamCandidateOrNull
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
 * Real streaming provider backed by the keyless Audius API (Phase 16) — serves **full-length** tracks,
 * unlike the iTunes 30-second previews. Two-phase resolution (NUCLEAR_UPSTREAM_STUDY.md §5): phase 1
 * text-searches Audius for candidates; phase 2 builds the just-in-time stream URL
 * (`/v1/tracks/{id}/stream`, a 302 to an ephemeral CDN file that ExoPlayer follows). Works for tracks
 * from any metadata provider (matches by artist/title). Resolved URLs are never persisted.
 */
class AudiusStreamingProvider(
    private val api: AudiusApi,
    private val hosts: AudiusHostProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StreamingProvider {

    override val id: String = AudiusIds.STREAMING
    override val kind: ProviderKind = ProviderKind.STREAMING
    override val name: String = "Audius"

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        val artist = track.artists.firstOrNull()?.name
        val term = listOfNotNull(artist, track.title).joinToString(" ").trim()
        if (term.isEmpty()) return emptyList()
        return guarded {
            val host = hosts.host()
            api.searchTracks("$host/v1/tracks/search", term, AudiusIds.APP_NAME, CANDIDATE_LIMIT)
                .data
                // Drop non-streamable rows and Audius's loose fuzzy matches: keeping only rows that
                // actually *are* the requested track lets the resolver fall back to another provider
                // (e.g. iTunes previews) rather than playing the wrong song or failing.
                .filter { it.isStreamable != false && it.matches(track.title, artist) }
                .mapNotNull { it.toStreamCandidateOrNull() }
        }
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream = guarded {
        val host = hosts.host()
        audiusStream(host, candidate.id, candidate.durationMs)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError) {
        throw e
    } catch (e: IOException) {
        throw AppError.Network(e.message ?: "connection failed", e)
    } catch (e: LinkageError) {
        // Errors, not Exceptions — a missing platform API must fail the provider, not the app.
        throw AppError.ProviderFailure(name, e.message ?: "audius client incompatible", e)
    } catch (e: Exception) {
        throw AppError.ProviderFailure(name, e.message ?: "audius request failed", e)
    }

    companion object {
        private const val CANDIDATE_LIMIT = 6
    }
}
