package fm.rizx.player.domain.model

/**
 * A playlist fetched from a URL, before it is saved locally (Phase 22). Its [tracks] are full [Track]s;
 * [origin] records where it came from. Saved read-only with a fresh local id (URL is not identity).
 */
data class PlaylistPreview(
    val name: String,
    val description: String? = null,
    val tracks: List<Track> = emptyList(),
    val origin: ProviderRef? = null,
    /** The playlist's own cover, when the source supplies one. Persisted with the saved playlist. */
    val artwork: ArtworkSet? = null,
)
