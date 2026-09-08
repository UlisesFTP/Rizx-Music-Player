package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.PlaylistItemEntity
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.data.local.store.PlaylistTransfer
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistDigest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/** Hard cap on tracks persisted from a single import — a hostile/huge file otherwise bloats the DB. */
private const val MAX_IMPORT_TRACKS = 10_000

/** A 2×2 collage needs four covers; carrying more would only be decoded artwork nobody draws. */
private const val COLLAGE_COVERS = 4

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

    /**
     * Decodes every item once per change and reduces it to covers and a total. Off the main thread
     * because a library of a few hundred songs is a few hundred JSON decodes, and this recomputes on
     * every playlist edit. A row this build cannot decode is skipped, never thrown (see [TrackJson]).
     */
    override fun playlistDigests(): Flow<Map<String, PlaylistDigest>> =
        dao.observeItemDigests()
            .map { rows ->
                rows.groupBy { it.playlistId }.mapValues { (_, items) ->
                    val tracks = items.mapNotNull { TrackJson.decodeTrackOrNull(it.trackJson) }
                    PlaylistDigest(
                        covers = tracks.mapNotNull { track -> track.artwork?.takeIf { it.items.isNotEmpty() } }.take(COLLAGE_COVERS),
                        durationMs = tracks.sumOf { it.durationMs ?: 0L },
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    override fun playlist(id: String): Flow<Playlist?> =
        combine(dao.observePlaylist(id), dao.observeItems(id)) { entity, items ->
            entity?.toDomain(items.mapNotNull { it.toDomain() })
        }

    override suspend fun createPlaylist(name: String, description: String?): String {
        val id = newId()
        val now = nowIso()
        val playlist = PlaylistEntity(
                id = id, name = name, description = description,
                createdAtIso = now, lastModifiedIso = now,
                isReadOnly = false, parentId = null, originProvider = null, originId = null,
            )
        dao.createWithJournal(playlist, journal(id, "UPSERT", now))
        return id
    }

    override suspend fun deletePlaylist(id: String) {
        dao.getPlaylist(id) ?: return
        val now = nowIso()
        dao.deleteWithJournal(id, journal(id, "DELETE", now))
    }

    override suspend fun rename(id: String, name: String, description: String?) {
        val existing = requireMutable(id)
        val now = nowIso()
        dao.updateWithJournal(
            existing.copy(name = name, description = description, lastModifiedIso = now),
            journal(id, "UPSERT", now),
        )
    }

    override suspend fun addTracks(playlistId: String, tracks: List<Track>) {
        val existing = requireMutable(playlistId)
        var order = dao.maxOrder(playlistId)
        val now = nowIso()
        val items = tracks.map { track ->
            PlaylistItemEntity(
                    id = newId(), playlistId = playlistId, sortOrder = ++order,
                    trackJson = TrackJson.encodeTrack(track), note = null, addedAtIso = now,
                )
        }
        if (items.isEmpty()) return
        dao.addItemsWithJournal(
            items,
            existing.copy(lastModifiedIso = now),
            journal(playlistId, "UPSERT", now),
        )
    }

    override suspend fun removeItem(playlistId: String, itemId: String) {
        val existing = requireMutable(playlistId)
        val now = nowIso()
        dao.removeItemWithJournal(
            itemId,
            existing.copy(lastModifiedIso = now),
            journal(playlistId, "UPSERT", now),
        )
    }

    override suspend fun reorder(playlistId: String, fromIndex: Int, toIndex: Int) {
        val existing = requireMutable(playlistId)
        val items = dao.getItems(playlistId).toMutableList()
        if (fromIndex !in items.indices) return
        val to = toIndex.coerceIn(0, items.lastIndex)
        if (to == fromIndex) return
        items.add(to, items.removeAt(fromIndex))
        val now = nowIso()
        dao.reorderWithJournal(
            items.map { it.id },
            existing.copy(lastModifiedIso = now),
            journal(playlistId, "UPSERT", now),
        )
    }

    override suspend fun saveQueueAsPlaylist(name: String, tracks: List<Track>): String {
        val id = createPlaylist(name)
        addTracks(id, tracks)
        return id
    }

    override suspend fun exportPlaylist(id: String): String? {
        val entity = dao.getPlaylist(id) ?: return null
        val tracks = dao.getItems(id).mapNotNull { TrackJson.decodeTrackOrNull(it.trackJson) }
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

        val decoded = items.mapNotNull { item ->
            TrackJson.decodeTrackOrNull(item.trackJson)?.let { item to it }
        }
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
        val playlist = PlaylistEntity(
                id = id, name = name, description = description,
                createdAtIso = now, lastModifiedIso = now,
                isReadOnly = false, parentId = null, originProvider = "import", originId = origin ?: id,
                artworkUrl = artworkUrl,
            )
        // Insert items directly rather than via addTracks() so lastModified isn't bumped per track.
        // Bounded: a hostile/oversized import must not bloat the DB or freeze the UI.
        val items = tracks.take(MAX_IMPORT_TRACKS).mapIndexed { index, track ->
            PlaylistItemEntity(
                    id = newId(), playlistId = id, sortOrder = index,
                    trackJson = TrackJson.encodeTrack(track), note = null, addedAtIso = now,
                )
        }
        dao.importWithJournal(playlist, items, journal(id, "UPSERT", now))
        return id
    }

    /** Returns the playlist entity, or throws if it is read-only. */
    private suspend fun requireMutable(id: String): PlaylistEntity {
        val entity = dao.getPlaylist(id) ?: throw ReadOnlyPlaylistException(id)
        if (entity.isReadOnly) throw ReadOnlyPlaylistException(id)
        return entity
    }

    private fun journal(id: String, operation: String, now: String) = SyncOutboxEntity(
        operationId = newId(), entityType = "PLAYLIST", entityId = id, operation = operation,
        payloadJson = null, createdAtIso = now,
    )

    private fun PlaylistEntity.toDomain(items: List<PlaylistItem>) = Playlist(
        id = id, name = name, description = description,
        artwork = artworkUrl?.takeIf { it.isNotBlank() }
            ?.let { ArtworkSet(listOf(Artwork(url = it, purpose = ArtworkPurpose.COVER))) },
        createdAtIso = createdAtIso, lastModifiedIso = lastModifiedIso,
        origin = if (originProvider != null && originId != null) ProviderRef(originProvider, originId) else null,
        isReadOnly = isReadOnly, parentId = parentId, items = items,
    )

    /** Null for a row this build cannot read; the playlist opens without it (TrackJson.decodeTrackOrNull). */
    private fun PlaylistItemEntity.toDomain(): PlaylistItem? {
        val track = TrackJson.decodeTrackOrNull(trackJson) ?: return null
        return PlaylistItem(id = id, track = track, note = note, addedAtIso = addedAtIso)
    }
}
