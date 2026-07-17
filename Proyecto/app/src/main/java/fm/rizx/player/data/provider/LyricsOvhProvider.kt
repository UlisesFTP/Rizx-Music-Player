package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.lyricsovh.LyricsOvhApi
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/**
 * Real lyrics provider backed by the keyless lyrics.ovh API (Phase 15). A 404 (no lyrics for the
 * track) is a normal `null` result, not a failure; connectivity problems surface as a typed
 * [AppError] so the UI shows an offline/error state instead of crashing.
 */
class LyricsOvhProvider(
    private val api: LyricsOvhApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LyricsProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.LYRICS
    override val name: String = "lyrics.ovh"

    override suspend fun getLyrics(track: Track): String? {
        val artist = track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return null
        val title = track.title.takeIf { it.isNotBlank() } ?: return null
        return try {
            withContext(io) { api.getLyrics(artist, title).lyrics?.trim()?.takeIf { it.isNotEmpty() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw AppError.ProviderFailure(name, "HTTP ${e.code()}", e)
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "lyrics lookup failed", e)
        }
    }

    companion object {
        const val ID = "lyrics-ovh"
    }
}
