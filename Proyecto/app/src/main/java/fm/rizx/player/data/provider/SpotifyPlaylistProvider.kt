package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.spotify.SpotifyEmbedEntity
import fm.rizx.player.data.remote.spotify.SpotifyEmbedNextData
import fm.rizx.player.data.remote.spotify.SpotifyIds
import fm.rizx.player.data.remote.spotify.SpotifyPathfinderClient
import fm.rizx.player.data.remote.spotify.spotifyPlaylistId
import fm.rizx.player.data.remote.spotify.toArtworkSet
import fm.rizx.player.data.remote.spotify.toTrackOrNull
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Track
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
 * the streaming providers, exactly like a Deezer track.
 *
 * The embed ships at most [EMBED_TRACK_CAP] rows, which used to be the whole import: a 900-track playlist
 * arrived as its first 100. When the list comes back at that cap, [pathfinder] pages the rest using the
 * anonymous token the same embed page publishes — see [SpotifyPathfinderClient] for why that stays inside
 * the keyless rule. Playlists shorter than the cap never touch it, so the charts dashboard (which reuses
 * this provider for Top 50 / Viral 50) costs exactly what it did before.
 *
 * The remaining limit is that it reads a web page, so it can break if Spotify changes it. Failures stay
 * contained as typed [AppError]s — a broken provider never crashes the app — and a *paging* failure is
 * softer still: the embed's first 100 tracks are already in hand, so the import degrades instead of dying.
 */
class SpotifyPlaylistProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** `%s` = playlist id. Overridden only by tests (to point at a local server). */
    private val embedUrlTemplate: String = EMBED_URL,
    /** `%s` = album id. Public embed, overridden by fixture tests. */
    private val albumEmbedUrlTemplate: String = ALBUM_EMBED_URL,
    /** Null disables paging entirely (the pre-existing embed-only behaviour). */
    private val pathfinder: SpotifyPathfinderClient? = null,
) : PlaylistProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.PLAYLISTS
    override val name: String = "Spotify Playlists"

    override fun canHandle(url: String): Boolean =
        url.contains("spotify", ignoreCase = true) && spotifyPlaylistId(url) != null

    override suspend fun fetchPlaylist(url: String): PlaylistPreview = fetchPlaylistInternal(url, enrichFirstPage = false)

    /**
     * Dashboard-only path: asks Pathfinder for page zero even when the embed has fewer than 100 rows.
     * Those rows carry album identity and cover art, which lets the chart expose real album cards.
     */
    suspend fun fetchPlaylistForDashboard(url: String): PlaylistPreview =
        fetchPlaylistInternal(url, enrichFirstPage = true)

    suspend fun fetchAlbum(source: fm.rizx.player.domain.model.ProviderRef): Album? = withContext(io) {
        if (source.provider != SpotifyIds.PROVIDER || !source.id.startsWith("album:")) return@withContext null
        val albumId = source.id.substringAfter(':').takeIf { it.isNotBlank() } ?: return@withContext null
        val entity = parseEmbed(get(albumEmbedUrlTemplate.format(albumId)))
            ?.props?.pageProps?.state?.data?.entity?.takeIf { it.type == ALBUM_TYPE } ?: return@withContext null
        val artwork = entity.coverArt.toArtworkSet()
        val ref = AlbumRef(entity.name.orEmpty(), artwork = artwork, source = source)
        val tracks = entity.trackList.mapNotNull { it.toTrackOrNull() }
            .map { track -> track.copy(album = ref, artwork = track.artwork ?: artwork) }
        Album(
            title = entity.name?.takeIf { it.isNotBlank() } ?: return@withContext null,
            artwork = artwork,
            tracks = tracks,
            totalTracks = tracks.size,
            source = source,
        )
    }

    private suspend fun fetchPlaylistInternal(url: String, enrichFirstPage: Boolean): PlaylistPreview {
        val playlistId = spotifyPlaylistId(url)
            ?: throw AppError.ProviderFailure(name, "no playlist id in URL")
        return try {
            withContext(io) {
                val next = parseEmbed(get(embedUrlTemplate.format(playlistId)))
                val entity = next?.props?.pageProps?.state?.data?.entity?.takeIf { it.type == PLAYLIST_TYPE }
                    ?: throw AppError.ProviderFailure(name, "couldn't read the playlist (Spotify may have changed its embed page)")
                val embedTracks = entity.trackList.mapNotNull { it.toTrackOrNull() }
                if (embedTracks.isEmpty()) throw AppError.ProviderFailure(name, "no readable tracks (is the playlist private?)")
                val token = next.props.pageProps.state.settings?.session?.accessToken
                val head = if (enrichFirstPage) enrichHead(playlistId, embedTracks, token) else embedTracks
                val tracks = extend(playlistId, head, token)
                PlaylistPreview(
                    name = entity.name?.takeIf { it.isNotBlank() } ?: "Spotify playlist",
                    description = describe(entity, imported = tracks.size),
                    tracks = tracks,
                    origin = SpotifyIds.playlist(playlistId),
                    // The embed does carry the playlist's own cover, even though its rows carry none.
                    artwork = entity.coverArt.toArtworkSet(),
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

    private fun enrichHead(playlistId: String, embedTracks: List<Track>, token: String?): List<Track> {
        val client = pathfinder ?: return embedTracks
        if (token.isNullOrBlank()) return embedTracks
        val rich = client.contents(playlistId, token, 0, PAGE_SIZE)?.items.orEmpty()
            .filter { it.itemV2?.__typename == TRACK_ITEM_TYPE }
            .mapNotNull { it.itemV2?.data?.toTrackOrNull() }
        if (rich.isEmpty()) return embedTracks
        val byId = rich.associateBy { it.source.identityKey }
        return embedTracks.map { byId[it.source.identityKey] ?: it }
    }

    /** Pulls the `__NEXT_DATA__` JSON out of the embed HTML. */
    private fun parseEmbed(html: String): SpotifyEmbedNextData? {
        val blob = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        return json.decodeFromString(SpotifyEmbedNextData.serializer(), blob)
    }

    /**
     * Pages past the embed cap, keeping [embedTracks] as the floor. Only runs when the embed came back
     * *at* the cap — anything shorter is already the whole playlist, and spending a request to confirm
     * that would tax every chart refresh for nothing.
     *
     * Each page is appended only while it keeps producing new rows; a null page (gateway down, token
     * expired, hash rotated past recovery) stops the walk and hands back whatever was collected, so the
     * worst case is exactly today's behaviour rather than a failed import.
     */
    private fun extend(playlistId: String, embedTracks: List<Track>, token: String?): List<Track> {
        val client = pathfinder ?: return embedTracks
        if (embedTracks.size < EMBED_TRACK_CAP) return embedTracks
        if (token.isNullOrBlank()) return embedTracks

        val collected = embedTracks.toMutableList()
        val seen = embedTracks.mapTo(mutableSetOf()) { it.source.identityKey }
        var offset = collected.size
        while (offset < MAX_TRACKS) {
            val page = client.contents(playlistId, token, offset, PAGE_SIZE) ?: break
            val fresh = page.items
                .filter { it.itemV2?.__typename == TRACK_ITEM_TYPE }
                .mapNotNull { it.itemV2?.data?.toTrackOrNull() }
                .filter { seen.add(it.source.identityKey) }
            // Advance by what the page *held*, not by what mapped: podcast episodes and unavailable
            // rows map to nothing, and advancing by the mapped count would re-request them forever.
            offset += page.items.size
            collected += fresh
            val total = page.totalCount
            if (page.items.isEmpty() || (total != null && offset >= total)) break
        }
        return collected
    }

    /**
     * The playlist's own subtitle, plus an honest heads-up when the list really was cut short — either
     * because paging never got past the embed, or because [MAX_TRACKS] stopped it.
     */
    private fun describe(entity: SpotifyEmbedEntity, imported: Int): String? = listOfNotNull(
        entity.subtitle?.takeIf { it.isNotBlank() },
        "First $imported tracks only — Spotify wouldn't serve the rest. Import a JSON/CSV export for the full list."
            .takeIf { imported == EMBED_TRACK_CAP || imported >= MAX_TRACKS },
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

        /** Matches the repository's own save ceiling, so the walk is never the shorter of the two. */
        const val MAX_TRACKS = 10_000

        /** What the web player itself asks for per page. */
        private const val PAGE_SIZE = 100

        private const val PLAYLIST_TYPE = "playlist"
        private const val ALBUM_TYPE = "album"
        private const val TRACK_ITEM_TYPE = "TrackResponseWrapper"
        const val EMBED_URL = "https://open.spotify.com/embed/playlist/%s"
        const val ALBUM_EMBED_URL = "https://open.spotify.com/embed/album/%s"
        private val NEXT_DATA =
            Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }
}
