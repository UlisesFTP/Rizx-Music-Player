package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * User playlists (§7.2). Each `PlaylistItem.id` is distinct from track identity, so the same track
 * can appear multiple times. Imported playlists are read-only — mutating them throws
 * [ReadOnlyPlaylistException]. Tracks are stored resolution-stripped (no persisted stream URLs).
 */
interface PlaylistRepository {

    fun playlists(): Flow<List<PlaylistSummary>>
    fun playlist(id: String): Flow<Playlist?>

    /** Creates an empty playlist and returns its new id. */
    suspend fun createPlaylist(name: String, description: String? = null): String

    suspend fun deletePlaylist(id: String)
    suspend fun rename(id: String, name: String, description: String?)

    suspend fun addTracks(playlistId: String, tracks: List<Track>)
    suspend fun removeItem(playlistId: String, itemId: String)
    suspend fun reorder(playlistId: String, fromIndex: Int, toIndex: Int)

    /** Saves a queue snapshot as a new playlist (resolution stripped); returns its id. */
    suspend fun saveQueueAsPlaylist(name: String, tracks: List<Track>): String

    /** Serializes playlist [id] to a portable JSON export, or `null` if it doesn't exist. */
    suspend fun exportPlaylist(id: String): String?

    /**
     * Imports a playlist **file** — a Rizx export, a Nuclear playlist, or an Exportify CSV — as a new
     * playlist and returns its id. The result is a normal, **editable** playlist (saved immediately).
     * [fallbackName] names it when the format carries no name of its own (CSV); normally the file name.
     * Throws if [text] isn't a recognized playlist.
     */
    suspend fun importPlaylistFile(text: String, fallbackName: String? = null): String

    /**
     * Imports a playlist by URL (Deezer, Spotify, YouTube/YT Music, or a hosted export file): the first
     * enabled playlist provider that can handle [url] fetches it, and it is saved immediately as a normal,
     * **editable** playlist. Throws if no provider matches or the fetch fails.
     */
    suspend fun importFromUrl(url: String): String

    /**
     * Fetches a **remote** playlist's tracks **without saving it** — for previewing/playing a playlist
     * found in search. Reconstructs the source URL from [source] (or uses [ProviderRef.url]) and routes to
     * the matching playlist provider (Deezer / YouTube / Spotify). Returns empty if none can serve it.
     */
    suspend fun previewPlaylist(source: ProviderRef): List<Track>
}

class ReadOnlyPlaylistException(id: String) : Exception("Playlist $id is read-only")
