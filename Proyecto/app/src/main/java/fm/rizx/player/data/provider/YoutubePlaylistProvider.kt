package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.data.remote.youtube.isImportableYoutubePlaylistId
import fm.rizx.player.data.remote.youtube.toTrackOrNull
import fm.rizx.player.data.remote.youtube.youtubePlaylistId
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Imports **YouTube and YouTube Music** playlists by URL, keyless via NewPipeExtractor (already the app's
 * YouTube engine — no API key, no new HTTP client). NewPipe's link handler accepts `youtube.com`, `m.`,
 * **`music.youtube.com`** and `watch?v=…&list=…` alike, and the client follows pagination so playlists
 * longer than one ~100-item page import in full.
 *
 * Unlike Spotify, these tracks arrive **already identified**: each keeps its video id as [Track.source], so
 * `YoutubeStreamingProvider` plays that exact video instead of re-searching it by artist/title.
 */
class YoutubePlaylistProvider(
    private val client: YoutubeExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.PLAYLISTS
    override val name: String = "YouTube Playlists"

    override fun canHandle(url: String): Boolean =
        youtubePlaylistId(url)?.let { isImportableYoutubePlaylistId(it) } ?: false

    override suspend fun fetchPlaylist(url: String): PlaylistPreview {
        val playlistId = youtubePlaylistId(url)
            ?: throw AppError.ProviderFailure(name, "not a YouTube playlist URL")
        if (!isImportableYoutubePlaylistId(playlistId)) {
            throw AppError.ProviderFailure(name, "mixes/radio and private lists (Liked music, Watch later) can't be imported")
        }
        return guarded {
            val data = client.playlist(url)
            val tracks = data.items.mapNotNull { it.toTrackOrNull() }
            if (tracks.isEmpty()) throw AppError.ProviderFailure(name, "playlist has no importable tracks")
            PlaylistPreview(
                name = data.name?.takeIf { it.isNotBlank() } ?: "YouTube playlist",
                description = listOfNotNull(
                    data.uploaderName?.takeIf { it.isNotBlank() },
                    "Only the first ${tracks.size} tracks were imported.".takeIf { data.truncated },
                ).joinToString(" · ").takeIf { it.isNotBlank() },
                tracks = tracks,
                origin = YoutubeIds.playlist(playlistId),
            )
        }
    }

    /** Same error funnel as `YoutubeStreamingProvider`: NewPipe's exceptions become typed [AppError]s. */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError) {
        throw e
    } catch (e: IOException) {
        throw AppError.Network(e.message ?: "connection failed", e)
    } catch (e: LinkageError) {
        // Errors, not Exceptions — a missing platform API in the extractor must not crash the app.
        throw AppError.ProviderFailure(name, e.message ?: "youtube extractor incompatible", e)
    } catch (e: Exception) {
        // NewPipe throws ExtractionException/ReCaptchaException/ParsingException — surface as a typed
        // provider failure so a broken import never crashes the app.
        throw AppError.ProviderFailure(name, e.message ?: "playlist import failed", e)
    }

    companion object {
        const val ID = "youtube-playlists"
    }
}
