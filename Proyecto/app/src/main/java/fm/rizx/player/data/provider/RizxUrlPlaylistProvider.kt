package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.local.store.PlaylistTransfer
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.share.ShareLinks
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
 *
 * It is also the importer for **Rizx share links** (spec 021): a link the app hands out is
 * `<shareBaseUrl>/<token>`, and GETting it returns the shared playlist's Rizx JSON document, which the
 * same decode path already understands. Without this rule no provider matched a share URL (it has no
 * file extension), so pasting one showed "Error al importar" before any network call was made.
 *
 * A share link is **fetched from [shareReadEndpoint]**, not from its own address, and always as JSON.
 * The link's host is whatever the install hands out — today the function itself, tomorrow a domain
 * that serves a landing page to browsers and `assetlinks.json` to Android — while the document always
 * lives at the function. Tying the fetch to the endpoint is what lets the share host change without
 * the importer noticing.
 */
class RizxUrlPlaylistProvider(
    private val client: OkHttpClient,
    private val shareBaseUrl: String = "",
    shareReadEndpoint: String = "",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.PLAYLISTS
    override val name: String = "Playlist file (URL)"

    /** Normalized share-link prefix; blank when the install has no share backend configured. */
    private val shareBase = shareBaseUrl.trim().trimEnd('/').lowercase()

    /** Where share documents are read from; blank falls back to GETting the link as written. */
    private val readEndpoint = shareReadEndpoint.trim().trimEnd('/')

    override fun canHandle(url: String): Boolean {
        val u = url.lowercase()
        if (shareBase.isNotEmpty() && u.startsWith("$shareBase/")) return true
        return u.startsWith("http") &&
            (u.endsWith(".json") || u.endsWith(".csv") || u.contains("gist") || u.contains("pastebin") || u.contains("raw."))
    }

    override suspend fun fetchPlaylist(url: String): PlaylistPreview {
        return try {
            val shareToken = ShareLinks.tokenFrom(url, shareBaseUrl)
            val target = if (shareToken != null && readEndpoint.isNotEmpty()) "$readEndpoint/$shareToken" else url
            val body = withContext(io) { get(target, asJson = shareToken != null) }
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

    private fun get(url: String, asJson: Boolean = false): String {
        // The share function serves a browser landing page to `text/html`; say what this caller is.
        val request = Request.Builder().url(url).apply { if (asJson) header("Accept", "application/json") }.build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val source = resp.body?.source() ?: throw IOException("empty body")
            // The body is controlled by whoever hosts the URL (a link a third party may have handed the
            // user), so bound it: without this a multi-hundred-MB response OOMs the app during import.
            if (source.request(MAX_IMPORT_BYTES + 1L)) throw IOException("playlist file too large")
            return source.readUtf8()
        }
    }

    /** `…/my-list.csv?x=1` → `my-list`. */
    private fun fileNameFromUrl(url: String): String? =
        url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').takeIf { it.isNotBlank() }

    companion object {
        const val ID = "rizx-url-playlists"
        private const val MAX_IMPORT_BYTES = 8L * 1024 * 1024
    }
}
