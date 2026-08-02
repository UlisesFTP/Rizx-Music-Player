package fm.rizx.player.domain.model

/**
 * Lightweight playlist projection for list views (§7.2 two-tier) — cheap to load, no [Playlist.items].
 * The full [Playlist] is fetched on demand.
 */
data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val itemCount: Int = 0,
    val isReadOnly: Boolean = false,
    /** Cover for the list row, when one is known. A plain URL — list rows only ever need one size. */
    val artworkUrl: String? = null,
    /** True for playlists that arrived via an import (Spotify/YouTube/Deezer URL or file), not made here. */
    val isImported: Boolean = false,
)
