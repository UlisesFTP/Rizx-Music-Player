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
)
