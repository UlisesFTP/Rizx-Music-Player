package fm.rizx.player.domain.discovery

import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.SoundGenre
import kotlinx.coroutines.flow.Flow

/**
 * One taste artist a discovery shelf is built around.
 *
 * [deezerId] travels with the name because the Deezer sources key everything by it and the For-you
 * repository has usually resolved it already for the other rows — asking twice would be the burst the
 * shared artist search exists to prevent.
 */
data class DiscoveryAnchor(val name: String, val deezerId: String? = null)

/**
 * A platform that can answer *"which playlists would this listener like?"* — keyless, like every source
 * in this app (ADR 0018, ADR 0033).
 *
 * Both methods answer empty for "nothing here" and for "this platform can't answer that" alike, and a
 * failure is the caller's to isolate: one platform down must cost its cards, never the shelf.
 */
interface DiscoveryPlaylistSource {

    /** Stable id, for logs and tests. */
    val id: String

    /** Editorial playlists that feature [anchor]. Most relevant first. */
    suspend fun forArtist(anchor: DiscoveryAnchor): List<PlaylistRef> = emptyList()

    /** Playlists for one [genre]. Most relevant first. */
    suspend fun forGenre(genre: SoundGenre): List<PlaylistRef> = emptyList()
}

/**
 * The listener's "not interested" list, kept on the device.
 *
 * Keys are the same identity strings the Home dedupes by (artist name key, playlist identity key), so a
 * hidden artist disappears from every shelf at once, whichever platform supplied it.
 */
interface DiscoveryFeedback {
    val hidden: Flow<HiddenItems>
    suspend fun hideArtist(name: String)
    suspend fun hidePlaylist(identityKey: String)
    suspend fun unhideArtist(name: String)
    suspend fun unhidePlaylist(identityKey: String)
    suspend fun clear()
}

/** A snapshot of [DiscoveryFeedback]. Artist keys are [fm.rizx.player.domain.usecase.ArtistNameMatching] keys. */
data class HiddenItems(
    val artistKeys: Set<String> = emptySet(),
    val playlistKeys: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = artistKeys.isEmpty() && playlistKeys.isEmpty()
}

/**
 * The artists a listener with no history picked to start from — the cold-start answer every streaming
 * app gives instead of an empty "For you".
 */
interface TasteSeeds {
    val seeds: Flow<List<String>>

    /** Whether the picker was dismissed with "Not now". Remembered so it is asked once, not every launch. */
    val dismissed: Flow<Boolean>
    suspend fun setSeeds(names: List<String>)
    suspend fun dismiss()
}
