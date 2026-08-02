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
import fm.rizx.player.core.network.DataSaverState
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
    private val dataSaver: DataSaverState,
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
            maxQuality = !forDownload && dataSaver.effectiveQualityMode().prefersBestCompressed,
        ) ?: throw AppError.ProviderFailure(name, "no audio stream for ${candidate.id}")
    }

    /**
     * Max quality by default; drop to a lower bitrate when the user asked to save data, or when the link
     * is too weak to carry the good stream anyway.
     *
     * **No network condition on the saving half any more.** It used to require `isCellular`, which meant
     * the switch did nothing on home Wi-Fi *and* nothing on a phone hotspot — the one case where it
     * matters most, since a hotspot reports Wi-Fi transport while billing somebody's data plan. The
     * switch now means what it says on every connection; [DataSaverState] is where that is decided.
     *
     * A weak signal stays a separate trigger and is not data saving: it is the link not managing more.
     */
    private suspend fun shouldPreferLow(): Boolean =
        dataSaver.saving.first() || networkMonitor.snapshot().isBadSignal

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
