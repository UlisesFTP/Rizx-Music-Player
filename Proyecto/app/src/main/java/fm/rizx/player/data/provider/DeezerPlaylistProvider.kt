package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Imports Deezer playlists by URL (Phase 22). Recognizes `…deezer.com/…/playlist/<id>` (and the API
 * form) and fetches `/playlist/{id}` (keyless). Result is saved read-only by the repository.
 */
class DeezerPlaylistProvider(
    private val api: DeezerApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.PLAYLISTS
    override val name: String = "Deezer Playlists"

    override fun canHandle(url: String): Boolean =
        url.contains("deezer", ignoreCase = true) && PLAYLIST_ID.containsMatchIn(url)

    override suspend fun fetchPlaylist(url: String): PlaylistPreview {
        val playlistId = PLAYLIST_ID.find(url)?.groupValues?.get(1)
            ?: throw AppError.ProviderFailure(name, "no playlist id in URL")
        return try {
            withContext(io) {
                val dto = api.playlist(playlistId)
                PlaylistPreview(
                    name = dto.title ?: "Imported playlist",
                    description = dto.description,
                    tracks = dto.tracks?.data?.mapNotNull { it.toTrackOrNull() }.orEmpty(),
                    origin = dto.id?.let { DeezerIds.playlist(it) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "playlist fetch failed", e)
        }
    }

    companion object {
        const val ID = "deezer-playlists"
        private val PLAYLIST_ID = Regex("""playlist/(\d+)""")
    }
}
