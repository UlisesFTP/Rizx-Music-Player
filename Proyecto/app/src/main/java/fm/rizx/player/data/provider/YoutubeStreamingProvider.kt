package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.data.remote.youtube.toBestAudioStreamOrNull
import fm.rizx.player.data.remote.youtube.toStreamCandidateOrNull
import fm.rizx.player.data.remote.youtube.toYoutubeCandidateOrNull
import fm.rizx.player.data.remote.youtube.youtubeWatchUrl
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
 * Native **full-length** YouTube streaming provider (ADR 0014) — the reliable "songs play in full" path,
 * and the native replacement for Nuclear's yt-dlp YouTube plugin (which cannot run in an Android JS
 * engine). Two-phase resolution (NUCLEAR_UPSTREAM_STUDY.md §5): phase 1 text-searches YouTube for song
 * candidates; phase 2 extracts a just-in-time audio-only stream URL (a short-lived googlevideo host that
 * ExoPlayer plays directly). Works for tracks from any metadata provider (matches by artist/title).
 * Resolved URLs are never persisted. Sits at the top of the streaming fallback chain.
 */
class YoutubeStreamingProvider(
    private val client: YoutubeExtractorClient,
    private val networkMonitor: NetworkMonitor,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StreamingProvider {

    override val id: String = YoutubeIds.STREAMING
    override val kind: ProviderKind = ProviderKind.STREAMING
    override val name: String = "YouTube"

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        // A track that already knows its video (imported from a YouTube playlist) skips the search: it
        // plays that exact video instead of whatever an artist/title query happens to match, one round
        // trip sooner.
        track.toYoutubeCandidateOrNull()?.let { return listOf(it) }
        val term = listOfNotNull(track.artists.firstOrNull()?.name, track.title).joinToString(" ").trim()
        if (term.isEmpty()) return emptyList()
        return guarded {
            client.searchSongs(term, CANDIDATE_LIMIT).mapNotNull { it.toStreamCandidateOrNull() }
        }
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream = stream(candidate, forDownload = false)

    /**
     * Downloads deliberately stay on the standard (M4A) pick even in Hi-Res mode: `AudioTagWriter` can
     * only write tags into MP4/MP3/FLAC/Ogg containers, so an Opus-in-WebM download would land with no
     * title, artist or cover and export to the music library that way. Streaming keeps the better codec.
     */
    override suspend fun getDownloadStreamUrl(candidate: StreamCandidate): Stream =
        stream(candidate, forDownload = true)

    private suspend fun stream(candidate: StreamCandidate, forDownload: Boolean): Stream = guarded {
        val url = candidate.source.url ?: youtubeWatchUrl(candidate.id)
        client.streamInfo(url).toBestAudioStreamOrNull(
            candidate,
            preferLow = shouldPreferLow(),
            maxQuality = !forDownload && settings.hiResOutput.first(),
        ) ?: throw AppError.ProviderFailure(name, "no audio stream for ${candidate.id}")
    }

    /**
     * Max quality by default; drop to a lower bitrate only when the user turns on Data saver — on
     * cellular, or on a link too weak to carry the good stream. A weak signal on its own no longer
     * downgrades: the estimate is unreliable, and silently serving the worst stream to someone who never
     * asked to save data was the app's biggest hidden quality leak.
     */
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
        // provider failure so the resolver falls back to Audius/iTunes instead of crashing.
        throw AppError.ProviderFailure(name, e.message ?: "youtube extraction failed", e)
    }

    private companion object {
        const val CANDIDATE_LIMIT = 5
    }
}
