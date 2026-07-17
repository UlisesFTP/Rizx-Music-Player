package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.PlaylistPreview

/**
 * A playlist provider (Phase 22): recognizes and fetches a playlist from a URL. A [ProviderDescriptor]
 * of kind [ProviderKind.PLAYLISTS] — a **URL-matched fan-out** kind: import queries the enabled playlist
 * providers and uses the first whose [canHandle] returns true (NUCLEAR_UPSTREAM_STUDY.md §4/§7.2).
 */
interface PlaylistProvider : ProviderDescriptor {
    /** Cheap, offline check: can this provider import [url]? */
    fun canHandle(url: String): Boolean

    /** Fetches the playlist at [url]. Throws (typically [fm.rizx.player.core.error.AppError]) on failure. */
    suspend fun fetchPlaylist(url: String): PlaylistPreview
}
