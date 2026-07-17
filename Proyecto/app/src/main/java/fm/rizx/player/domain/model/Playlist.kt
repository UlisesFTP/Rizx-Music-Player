package fm.rizx.player.domain.model

/**
 * An entry in a [Playlist]. [id] is its **own** identity (not the track's), so the same track may
 * appear multiple times in one playlist. [addedAtIso] is an ISO-8601 timestamp.
 */
data class PlaylistItem(
    val id: String,
    val track: Track,
    val note: String? = null,
    val addedAtIso: String,
)

/**
 * A user (or imported) playlist. [origin] (note: not "source") records an imported playlist's
 * upstream ref. [createdAtIso]/[lastModifiedIso] are ISO-8601. Resolved stream URLs are never
 * stored in [items] — strip resolution state before persisting (Phase 10).
 */
data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val artwork: ArtworkSet? = null,
    val tags: List<String> = emptyList(),
    val createdAtIso: String,
    val lastModifiedIso: String,
    val origin: ProviderRef? = null,
    val isReadOnly: Boolean = false,
    val parentId: String? = null,
    val items: List<PlaylistItem> = emptyList(),
)
