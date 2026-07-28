package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.remote.soundcloud.SoundcloudIds
import fm.rizx.player.data.remote.soundcloud.toSoundcloudCandidateOrNull
import fm.rizx.player.data.remote.youtube.toBestAudioStreamOrNull
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.StreamingProvider
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Native **SoundCloud** streaming provider, keyless via NewPipeExtractor — the home of indie and emerging
 * artists the mainstream catalogs miss. Mirrors [YoutubeStreamingProvider]'s two-phase shape: phase 1
 * finds a candidate (short-circuiting when the track is already a SoundCloud permalink, e.g. tapped in the
 * Underground search tab), phase 2 resolves the just-in-time audio URL (progressive MP3/Opus, or HLS).
 * Resolved URLs are ephemeral. A failure funnels to a typed [AppError] so the resolver falls back down the
 * chain instead of crashing.
 */
class SoundcloudStreamingProvider(
    private val client: SoundcloudExtractorClient,
    private val networkMonitor: NetworkMonitor,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StreamingProvider {

    override val id: String = SoundcloudIds.STREAMING
    override val kind: ProviderKind = ProviderKind.STREAMING
    override val name: String = "SoundCloud"

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        // A track that already IS a SoundCloud track plays that exact permalink, no re-search.
        track.toSoundcloudCandidateOrNull()?.let { return listOf(it) }
        val term = listOfNotNull(track.artists.firstOrNull()?.name, track.title).joinToString(" ").trim()
        if (term.isEmpty()) return emptyList()
        return guarded {
            client.searchTracks(term, CANDIDATE_LIMIT).mapNotNull { it.toSoundcloudCandidateOrNull() }
        }
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream = guarded {
        val url = candidate.source.url ?: candidate.id
        client.streamInfo(url).toBestAudioStreamOrNull(candidate, preferLow = shouldPreferLow())
            ?: throw AppError.ProviderFailure(name, "no audio stream for ${candidate.title}")
    }

    /** Max quality by default; lower only when the user asked to save data (see the YouTube provider). */
    private suspend fun shouldPreferLow(): Boolean {
        val net = networkMonitor.snapshot()
        return settings.dataSaver.first() && (net.isCellular || net.isBadSignal)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError) {
        throw e
    } catch (e: IOException) {
        throw AppError.Network(e.message ?: "connection failed", e)
    } catch (e: Exception) {
        // NewPipe throws ExtractionException/ReCaptchaException/ParsingException — surface as a typed
        // provider failure so a broken SoundCloud call never crashes the app.
        throw AppError.ProviderFailure(name, e.message ?: "soundcloud extraction failed", e)
    }

    private companion object {
        const val CANDIDATE_LIMIT = 5
    }
}
