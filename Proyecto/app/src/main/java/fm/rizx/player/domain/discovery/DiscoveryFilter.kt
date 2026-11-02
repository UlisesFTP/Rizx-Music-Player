package fm.rizx.player.domain.discovery

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.usecase.ArtistNameMatching

/**
 * Takes what the listener said "not interested" to out of a Home that is about to be drawn.
 *
 * Applied at render time, over whatever is on hand — including the cached Home from the last launch — so
 * a hidden artist never comes back from the cache, and unhiding one brings it straight back without a
 * network round trip. An artist is matched by name key across every shelf (their songs, their albums,
 * their circle), whichever platform supplied it; a playlist by its source identity.
 *
 * A personalized row emptied by the filter disappears; one merely thinned stays — the listener asked for
 * one thing gone, not for the shelf around it. Pure.
 */
object DiscoveryFilter {

    data class Filtered(val feed: HomeFeed, val sections: List<ForYouSection>)

    fun apply(feed: HomeFeed, sections: List<ForYouSection>, hidden: HiddenItems): Filtered {
        if (hidden.isEmpty) return Filtered(feed, sections)
        val keep = Keep(hidden)
        return Filtered(
            feed = HomeFeed(
                topTracks = feed.topTracks.filterItems(keep::track),
                topArtists = feed.topArtists.filterItems { keep.artist(it.name) },
                topAlbums = feed.topAlbums.filterItems(keep::album),
                editorialPlaylists = feed.editorialPlaylists.filterItems(keep::playlist),
                newReleases = feed.newReleases.filterItems(keep::album),
                featured = feed.featured.filterItems { keep.playlist(it.playlist) },
                stations = feed.stations,
            ),
            sections = sections.mapNotNull { section ->
                when (section) {
                    is ForYouSection.Mix -> section.copy(items = section.items.filter(keep::track))
                    is ForYouSection.BecauseYouLike ->
                        section.takeIf { keep.artist(it.artistName) }?.copy(items = section.items.filter(keep::track))
                    is ForYouSection.SimilarTo -> section.takeIf { keep.artist(it.anchorName) }?.copy(
                        artists = section.artists.filter { keep.artist(it.name) },
                        albums = section.albums.filter(keep::album),
                    )
                    is ForYouSection.PlaylistsWith ->
                        section.takeIf { keep.artist(it.anchorName) }?.copy(playlists = section.playlists.filter(keep::playlist))
                    is ForYouSection.GenrePlaylists -> section.copy(playlists = section.playlists.filter(keep::playlist))
                    is ForYouSection.NewFromArtists -> section.copy(albums = section.albums.filter(keep::album))
                }?.takeIf { it.size > 0 }
            },
        )
    }

    private class Keep(private val hidden: HiddenItems) {
        fun artist(name: String): Boolean = ArtistNameMatching.key(name) !in hidden.artistKeys
        fun track(track: Track): Boolean = track.artists.all { artist(it.name) }
        fun album(album: AlbumRef): Boolean = album.artists.all { artist(it.name) }
        fun playlist(playlist: PlaylistRef): Boolean = playlist.source.identityKey !in hidden.playlistKeys
    }

    private fun <T> List<AttributedResult<T>>.filterItems(predicate: (T) -> Boolean): List<AttributedResult<T>> =
        mapNotNull { result -> result.items.filter(predicate).takeIf { it.isNotEmpty() }?.let { result.copy(items = it) } }
}
