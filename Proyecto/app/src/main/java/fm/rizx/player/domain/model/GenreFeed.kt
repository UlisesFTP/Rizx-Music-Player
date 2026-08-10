package fm.rizx.player.domain.model

/**
 * What one **genre** has to offer, as a dashboard provider sees it: the songs charting in it plus the
 * playlists, artists and albums that belong to it.
 *
 * This is deliberately not a [SearchResults]. Searching a catalogue for the word "Pop" returns songs
 * *titled* Pop and artists *named* Pop Smoke; a genre is a facet of the catalogue, not a query, and the
 * provider that owns the catalogue is the only thing that can group by it.
 */
data class GenreFeed(
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && playlists.isEmpty() && artists.isEmpty() && albums.isEmpty()
}
