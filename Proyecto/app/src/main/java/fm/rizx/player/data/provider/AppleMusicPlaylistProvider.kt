package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.applemusic.AppleMusicIds
import fm.rizx.player.data.remote.applemusic.AppleMusicPlaylistPage
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens an Apple Music editorial playlist — "Today's Hits", "Top 100: Global", the Essentials.
 *
 * Two steps, and the second one is the point. Apple's playlist page publishes a `schema.org`
 * `MusicPlaylist` block listing every track, but those rows carry only a title, a duration and a URL
 * ending in the catalogue id — **no artist**, and a 16:9 social-card image instead of a cover. Shown
 * as-is they would be unplayable (the streaming chain matches on artist + title) and would look
 * wrong. So the ids go straight back to the owning catalogue in one batched lookup, which returns the
 * real artist, album and square artwork.
 *
 * That round trip is why this provider exists at all rather than the dashboard emitting playlists it
 * cannot open: a playlist card that opens empty is worse than no card.
 */
class AppleMusicPlaylistProvider(
    private val page: AppleMusicPlaylistPage,
    private val catalogue: AppleMusicMetadataProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistProvider {

    override val id: String = "applemusic-playlists"
    override val kind: ProviderKind = ProviderKind.PLAYLISTS
    override val name: String = "Apple Music Playlists"

    override fun canHandle(url: String): Boolean =
        url.startsWith("http") && HOST in url.lowercase() && "/playlist/" in url.lowercase()

    override suspend fun fetchPlaylist(url: String): PlaylistPreview = withContext(io) {
        val parsed = page.fetch(url)
            ?: throw AppError.ProviderFailure(name, "couldn't read that Apple Music playlist")

        val hydrated = catalogue.trackDetails(parsed.rows.map { it.trackId })
        val byId = hydrated.associateBy { it.source.id }
        // Keep Apple's running order — a lookup returns rows in its own order, and the sequence is
        // part of what an editorial playlist *is*.
        val tracks = parsed.rows.mapNotNull { row -> byId[row.trackId] ?: row.toBareTrack() }

        PlaylistPreview(
            name = parsed.name,
            tracks = tracks,
            origin = ProviderRef(AppleMusicIds.PROVIDER, "playlist:${playlistId(url) ?: parsed.name}", url),
            artwork = tracks.firstNotNullOfOrNull { it.artwork },
        )
    }

    /**
     * The fallback when the lookup couldn't hydrate a row: keep it, with the id as identity and no
     * artist. It won't resolve to audio, but dropping it would silently shorten a "100 songs"
     * playlist to 94 with nothing to explain the gap.
     */
    private fun fm.rizx.player.data.remote.applemusic.ApplePlaylistRow.toBareTrack(): Track? {
        val name = title.takeIf { it.isNotBlank() } ?: return null
        return Track(
            title = name,
            artists = emptyList<ArtistCredit>(),
            durationMs = durationMs,
            source = AppleMusicIds.trackRef(trackId.toLongOrNull() ?: return null),
        )
    }

    private fun playlistId(url: String): String? = PLAYLIST_ID.find(url)?.groupValues?.get(1)

    private companion object {
        const val HOST = "music.apple.com"
        val PLAYLIST_ID = Regex("""/playlist/[^/]*/(pl\.[A-Za-z0-9]+)""")
    }
}
