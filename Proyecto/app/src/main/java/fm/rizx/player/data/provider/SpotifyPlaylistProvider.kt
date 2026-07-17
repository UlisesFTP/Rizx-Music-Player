package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.spotify.SpotifyEmbedEntity
import fm.rizx.player.data.remote.spotify.SpotifyEmbedNextData
import fm.rizx.player.data.remote.spotify.SpotifyIds
import fm.rizx.player.data.remote.spotify.spotifyPlaylistId
import fm.rizx.player.data.remote.spotify.toTrackOrNull
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Imports Spotify playlists by URL, **keyless**: it reads the public embed page
 * (`open.spotify.com/embed/playlist/<id>`), whose `__NEXT_DATA__` script carries the tracklist as JSON —
 * no API key, no token, no login. (Upstream Nuclear instead embeds a rotating TOTP secret to mint an
 * anonymous bearer for the official API; that would break the project's keyless rule and Spotify rotates
 * the secret, so their import breaks periodically.)
 *
 * Spotify supplies **metadata only** — each track resolves to audio at play time by artist+title through
 * the streaming providers, exactly like a Deezer track. Two known limits, both deliberate:
 * - the embed returns at most [EMBED_TRACK_CAP] tracks (longer lists → import a JSON/CSV export instead),
 * - it reads a web page, so it can break if Spotify changes it. Failures stay contained as typed
 *   [AppError]s — a broken provider never crashes the app, and the JSON/CSV path remains as a fallback.
 */
class SpotifyPlaylistProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** `%s` = playlist id. Overridden only by tests (to point at a local server). */
    private val embedUrlTemplate: String = EMBED_URL,
) : PlaylistProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.PLAYLISTS
    override val name: String = "Spotify Playlists"

    override fun canHandle(url: String): Boolean =
        url.contains("spotify", ignoreCase = true) && spotifyPlaylistId(url) != null

    override suspend fun fetchPlaylist(url: String): PlaylistPreview {
        val playlistId = spotifyPlaylistId(url)
            ?: throw AppError.ProviderFailure(name, "no playlist id in URL")
        return try {
            withContext(io) {
                val entity = parseEmbed(get(embedUrlTemplate.format(playlistId)))
                    ?: throw AppError.ProviderFailure(name, "couldn't read the playlist (Spotify may have changed its embed page)")
                val tracks = entity.trackList.mapNotNull { it.toTrackOrNull() }
                if (tracks.isEmpty()) throw AppError.ProviderFailure(name, "no readable tracks (is the playlist private?)")
                PlaylistPreview(
                    name = entity.name?.takeIf { it.isNotBlank() } ?: "Spotify playlist",
                    description = describe(entity),
                    tracks = tracks,
                    origin = SpotifyIds.playlist(playlistId),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            throw e
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "playlist import failed", e)
        }
    }

    /** Pulls the `__NEXT_DATA__` JSON out of the embed HTML and walks down to the playlist entity. */
    private fun parseEmbed(html: String): SpotifyEmbedEntity? {
        val blob = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val next = json.decodeFromString(SpotifyEmbedNextData.serializer(), blob)
        return next.props?.pageProps?.state?.data?.entity?.takeIf { it.type == PLAYLIST_TYPE }
    }

    /** The playlist's own subtitle, plus an honest heads-up when the embed cap truncated the list. */
    private fun describe(entity: SpotifyEmbedEntity): String? = listOfNotNull(
        entity.subtitle?.takeIf { it.isNotBlank() },
        "First $EMBED_TRACK_CAP tracks only (Spotify embed limit) — import a JSON/CSV export for the full list."
            .takeIf { entity.trackList.size >= EMBED_TRACK_CAP },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

    private fun get(url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("empty body")
        }
    }

    companion object {
        const val ID = "spotify-playlists"

        /** The embed page returns at most this many tracks (verified against long public playlists). */
        const val EMBED_TRACK_CAP = 100

        private const val PLAYLIST_TYPE = "playlist"
        const val EMBED_URL = "https://open.spotify.com/embed/playlist/%s"
        private val NEXT_DATA =
            Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }
}
