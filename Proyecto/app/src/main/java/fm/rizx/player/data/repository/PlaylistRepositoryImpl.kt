package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.PlaylistItemEntity
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.data.local.store.PlaylistTransfer
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.coverUrl
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
    /** Fills in covers a source didn't supply. Null in tests that don't exercise artwork. */
    private val artwork: TrackArtworkEnricher? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowIso: () -> String = { Instant.now().toString() },
) : PlaylistRepository {

    override fun playlists(): Flow<List<PlaylistSummary>> =
        dao.observeSummaries().map { rows ->
            rows.map {
                PlaylistSummary(
                    it.id, it.name, it.description, it.itemCount, it.isReadOnly, it.artworkUrl,
                    isImported = it.originProvider != null,
                )
            }
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
        // Fill in what the source didn't give us: per-track covers (Spotify supplies none) and, only if the
        // playlist itself came without one, a cover borrowed from its tracks.
        val tracks = artwork?.enrich(preview.tracks) ?: preview.tracks
        val cover = preview.artwork.coverUrl() ?: artwork?.playlistCover(tracks)
        return saveImported(
            preview.name, preview.description, tracks, origin = preview.origin?.id, artworkUrl = cover,
        )
    }

    /**
     * Repairs covers on an already-saved playlist. Cheap no-op once everything has artwork, so callers can
     * fire it on every open. Failures are swallowed — a missing cover must never break opening a playlist.
     */
    override suspend fun backfillArtwork(id: String) {
        val enricher = artwork ?: return
        val entity = dao.getPlaylist(id) ?: return
        val items = dao.getItems(id)
        if (items.isEmpty()) return

        val decoded = items.map { it to TrackJson.decodeTrack(it.trackJson) }
        // Deliberately no early-out on "everything already has a cover": a playlist saved by the old
        // unverified resolver has covers on every row, and some of them are the wrong record's. Those
        // are re-checked (and withdrawn if they no longer verify) — the artwork cache makes the pass
        // free from the second open onwards.

        runCatching {
            val enriched = enricher.enrich(decoded.map { (_, track) -> track }, repairBorrowed = true)
            decoded.forEachIndexed { index, (item, before) ->
                val after = enriched[index]
                if (after.artwork.coverUrl() != before.artwork.coverUrl()) {
                    dao.updateItemTrack(item.id, TrackJson.encodeTrack(after))
                }
            }
            if (entity.artworkUrl == null) {
                enricher.playlistCover(enriched)?.let { dao.setArtworkUrl(id, it) }
            }
        }
    }

    override suspend fun previewPlaylist(source: ProviderRef): List<Track> {
        val url = playlistUrl(source) ?: return emptyList()
        val provider = playlistProviderFor(url) ?: return emptyList()
        return provider.fetchPlaylist(url).tracks
    }

    /**
     * The canonical playlist URL for a remote [source]. Lives in [PlaylistUrls] because the Home feed
     * asks the same question before drawing a card, to avoid showing one that would open empty.
     */
    private fun playlistUrl(source: ProviderRef): String? =
        fm.rizx.player.data.provider.PlaylistUrls.canonical(source)

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
    private suspend fun saveImported(
        name: String,
        description: String?,
        tracks: List<Track>,
        origin: String?,
        artworkUrl: String? = null,
    ): String {
        val id = newId()
        val now = nowIso()
        dao.insertPlaylist(
            PlaylistEntity(
                id = id, name = name, description = description,
                createdAtIso = now, lastModifiedIso = now,
                isReadOnly = false, parentId = null, originProvider = "import", originId = origin ?: id,
                artworkUrl = artworkUrl,
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
        artwork = artworkUrl?.takeIf { it.isNotBlank() }
            ?.let { ArtworkSet(listOf(Artwork(url = it, purpose = ArtworkPurpose.COVER))) },
        createdAtIso = createdAtIso, lastModifiedIso = lastModifiedIso,
        origin = if (originProvider != null && originId != null) ProviderRef(originProvider, originId) else null,
        isReadOnly = isReadOnly, parentId = parentId, items = items,
    )

    private fun PlaylistItemEntity.toDomain() = PlaylistItem(
        id = id, track = TrackJson.decodeTrack(trackJson), note = note, addedAtIso = addedAtIso,
    )
}
