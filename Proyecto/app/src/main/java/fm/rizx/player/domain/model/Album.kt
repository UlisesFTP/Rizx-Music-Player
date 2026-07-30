package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * A full album — the heavier counterpart to [AlbumRef], carrying the actual [tracks]. Identity is
 * [source] (a [ProviderRef]); no `id` of its own (NUCLEAR_UPSTREAM_STUDY.md §2). Pure Kotlin,
 * Android-free. Fetched on demand via `MetadataProvider.albumDetail`.
 */
@Serializable
data class Album(
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: Int? = null,
    /** Full release date (`YYYY-MM-DD`) when the provider gives one; [year] is the coarse fallback. */
    val releaseDateIso: String? = null,
    val artwork: ArtworkSet? = null,
    val tracks: List<Track> = emptyList(),
    val totalTracks: Int? = null,
    val durationMs: Long? = null,
    /**
     * Free-form labels from the source, genre first — the same slot [Track.tags] uses.
     *
     * It exists because **a track usually has no genre but its album does**: Deezer returns genres only on
     * `/album/{id}`, and that is the cheapest honest answer for the automatic equalizer to ask
     * ([fm.rizx.player.domain.usecase.AutoEqCurves]). Reading it through this field rather than a Deezer
     * call keeps the question provider-agnostic — a plugin's catalogue can answer it too.
     */
    val tags: List<String> = emptyList(),
    val source: ProviderRef,
)
