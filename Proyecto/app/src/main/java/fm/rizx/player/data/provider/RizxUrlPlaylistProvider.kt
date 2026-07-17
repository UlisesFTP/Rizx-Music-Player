package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.local.store.PlaylistTransfer
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Imports a **playlist file hosted at a URL** (Phase 22) — the URL counterpart to the SAF file import
 * (spec 012). Handles `.json` / `.csv` / gist / raw-hosting URLs, GETs the body via the shared
 * [OkHttpClient], and decodes any supported format with [PlaylistTransfer.decodeImport] (Rizx export,
 * Nuclear playlist, or Exportify CSV). Registered last, so the service-specific providers match first.
 */
class RizxUrlPlaylistProvider(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.PLAYLISTS
    override val name: String = "Playlist file (URL)"

    override fun canHandle(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("http") &&
            (u.endsWith(".json") || u.endsWith(".csv") || u.contains("gist") || u.contains("pastebin") || u.contains("raw."))
    }

    override suspend fun fetchPlaylist(url: String): PlaylistPreview {
        return try {
            val body = withContext(io) { get(url) }
            // A hosted CSV has no name of its own — fall back to the file name in the URL.
            val imported = PlaylistTransfer.decodeImport(body, fallbackName = fileNameFromUrl(url))
            PlaylistPreview(name = imported.name, description = imported.description, tracks = imported.tracks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw AppError.ProviderFailure(name, e.message ?: "not a playlist file", e)
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "import failed", e)
        }
    }

    private fun get(url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("empty body")
        }
    }

    /** `…/my-list.csv?x=1` → `my-list`. */
    private fun fileNameFromUrl(url: String): String? =
        url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').takeIf { it.isNotBlank() }

    companion object {
        const val ID = "rizx-url-playlists"
    }
}
