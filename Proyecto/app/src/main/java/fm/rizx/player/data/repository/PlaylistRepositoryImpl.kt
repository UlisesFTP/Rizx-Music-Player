package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.PlaylistItemEntity
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.local.store.PlaylistTransfer
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistItem
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.ReadOnlyPlaylistException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * Room-backed [PlaylistRepository]. Each added track becomes a [PlaylistItem] with a fresh id (via
 * [newId]) — distinct from track identity — and is stored resolution-stripped. Read-only playlists
 * reject mutations. [newId]/[nowIso] are injectable for tests.
 */
class PlaylistRepositoryImpl(
    private val dao: PlaylistDao,
    private val registry: ProviderRegistry? = null,
    private val enabled: EnabledProviderStore? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowIso: () -> String = { Instant.now().toString() },
) : PlaylistRepository {

    override fun playlists(): Flow<List<PlaylistSummary>> =
        dao.observeSummaries().map { rows ->
            rows.map { PlaylistSummary(it.id, it.name, it.description, it.itemCount, it.isReadOnly) }
        }

    override fun playlist(id: String): Flow<Playlist?> =
        combine(dao.observePlaylist(id), dao.observeItems(id)) { entity, items ->
            entity?.toDomain(items.map { it.toDomain() })
        }

    override suspend fun createPlaylist(name: String, description: String?): String {
        val id = newId()
        val now = nowIso()
        dao.insertPlaylist(
            PlaylistEntity(
                id = id, name = name, description = description,
                createdAtIso = now, lastModifiedIso = now,
                isReadOnly = false, parentId = null, originProvider = null, originId = null,
            ),
        )
        return id
    }

    override suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)

    override suspend fun rename(id: String, name: String, description: String?) {
        val existing = requireMutable(id)
        dao.updatePlaylist(existing.copy(name = name, description = description, lastModifiedIso = nowIso()))
    }

    override suspend fun addTracks(playlistId: String, tracks: List<Track>) {
        requireMutable(playlistId)
        var order = dao.maxOrder(playlistId)
        val now = nowIso()
        for (track in tracks) {
            dao.insertItem(
                PlaylistItemEntity(
                    id = newId(), playlistId = playlistId, sortOrder = ++order,
                    trackJson = TrackJson.encodeTrack(track), note = null, addedAtIso = now,
                ),
            )
        }
        touch(playlistId)
    }

    override suspend fun removeItem(playlistId: String, itemId: String) {
        requireMutable(playlistId)
        dao.deleteItem(itemId)
        touch(playlistId)
    }

    override suspend fun reorder(playlistId: String, fromIndex: Int, toIndex: Int) {
        requireMutable(playlistId)
        val items = dao.getItems(playlistId).toMutableList()
        if (fromIndex !in items.indices) return
        val to = toIndex.coerceIn(0, items.lastIndex)
        if (to == fromIndex) return
        items.add(to, items.removeAt(fromIndex))
        items.forEachIndexed { index, item -> dao.updateOrder(item.id, index) }
        touch(playlistId)
    }

    override suspend fun saveQueueAsPlaylist(name: String, tracks: List<Track>): String {
        val id = createPlaylist(name)
        addTracks(id, tracks)
        return id
    }

    override suspend fun exportPlaylist(id: String): String? {
        val entity = dao.getPlaylist(id) ?: return null
        val tracks = dao.getItems(id).map { TrackJson.decodeTrack(it.trackJson) }
        return PlaylistTransfer.encode(entity.name, entity.description, tracks, nowIso())
    }

    override suspend fun importPlaylistFile(text: String, fallbackName: String?): String {
        val imported = PlaylistTransfer.decodeImport(text, fallbackName) // throws on unrecognized format
        return saveImported(imported.name, imported.description, imported.tracks, origin = null)
    }

    override suspend fun importFromUrl(url: String): String {
        if (registry == null) throw AppError.ProviderFailure("Import", "URL import unavailable")
        val provider = playlistProviderFor(url)
            ?: throw AppError.ProviderFailure("Import", "No provider can import this URL")
        val preview = provider.fetchPlaylist(url)
        return saveImported(preview.name, preview.description, preview.tracks, origin = preview.origin?.id)
    }

    override suspend fun previewPlaylist(source: ProviderRef): List<Track> {
        val url = playlistUrl(source) ?: return emptyList()
        val provider = playlistProviderFor(url) ?: return emptyList()
        return provider.fetchPlaylist(url).tracks
    }

    /**
     * The canonical playlist URL for a remote [source] — its [ProviderRef.url] if present, else rebuilt
     * from the namespaced id (`playlist:<raw>`). Mirrors the ids each search mapper emits, so the URL a
     * playlist provider's `canHandle` sees is the same shape a pasted link would be.
     */
    private fun playlistUrl(source: ProviderRef): String? {
        source.url?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = source.id.substringAfter(':').takeIf { it.isNotBlank() } ?: return null
        return when (source.provider) {
            "deezer" -> "https://www.deezer.com/playlist/$raw"
            "youtube" -> "https://www.youtube.com/playlist?list=$raw"
            "spotify" -> "https://open.spotify.com/playlist/$raw"
            else -> null
        }
    }

    /** The first enabled playlist provider that can handle [url] (registration order = priority). */
    private suspend fun playlistProviderFor(url: String): PlaylistProvider? {
        val reg = registry ?: return null
        val providers = reg.list(ProviderKind.PLAYLISTS).filterIsInstance<PlaylistProvider>()
        val enabledMap = enabled?.snapshot(providers.map { it.id }).orEmpty()
        return providers.filter { enabledMap[it.id] != false }.firstOrNull { it.canHandle(url) }
    }

    /**
     * Persists an imported playlist immediately (import always sticks — no extra "save" step) as a normal,
     * **editable** playlist with a fresh id and resolution-stripped items. [origin] keeps the provenance.
     */
    private suspend fun saveImported(name: String, description: String?, tracks: List<Track>, origin: String?): String {
        val id = newId()
        val now = nowIso()
        dao.insertPlaylist(
            PlaylistEntity(
                id = id, name = name, description = description,
                createdAtIso = now, lastModifiedIso = now,
                isReadOnly = false, parentId = null, originProvider = "import", originId = origin ?: id,
            ),
        )
        // Insert items directly rather than via addTracks() so lastModified isn't bumped per track.
        tracks.forEachIndexed { index, track ->
            dao.insertItem(
                PlaylistItemEntity(
                    id = newId(), playlistId = id, sortOrder = index,
                    trackJson = TrackJson.encodeTrack(track), note = null, addedAtIso = now,
                ),
            )
        }
        return id
    }

    /** Returns the playlist entity, or throws if it is read-only. */
    private suspend fun requireMutable(id: String): PlaylistEntity {
        val entity = dao.getPlaylist(id) ?: throw ReadOnlyPlaylistException(id)
        if (entity.isReadOnly) throw ReadOnlyPlaylistException(id)
        return entity
    }

    private suspend fun touch(id: String) {
        val entity = dao.getPlaylist(id) ?: return
        dao.updatePlaylist(entity.copy(lastModifiedIso = nowIso()))
    }

    private fun PlaylistEntity.toDomain(items: List<PlaylistItem>) = Playlist(
        id = id, name = name, description = description,
        createdAtIso = createdAtIso, lastModifiedIso = lastModifiedIso,
        origin = if (originProvider != null && originId != null) ProviderRef(originProvider, originId) else null,
        isReadOnly = isReadOnly, parentId = parentId, items = items,
    )

    private fun PlaylistItemEntity.toDomain() = PlaylistItem(
        id = id, track = TrackJson.decodeTrack(trackJson), note = note, addedAtIso = addedAtIso,
    )
}
