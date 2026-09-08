package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.FavoriteDao
import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.data.local.store.PortableTrackSanitizer
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Room-backed [FavoritesRepository]. Identity is `(type, provider, sourceId)`; the DAO's `IGNORE`
 * insert makes re-adding idempotent (original `addedAtIso` kept). [nowIso] is injectable for tests.
 */
class FavoritesRepositoryImpl(
    private val dao: FavoriteDao,
    private val nowIso: () -> String = { Instant.now().toString() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = Json { encodeDefaults = true },
) : FavoritesRepository {

    // `mapNotNull`, not `map`: these rows can come from another device, and a row this build
    // cannot read must cost that row rather than the screen (TrackJson.decodeTrackOrNull).
    override fun favoriteTracks(): Flow<List<Track>> =
        dao.observeByType(TRACK).map { rows -> rows.mapNotNull { TrackJson.decodeTrackOrNull(it.json) } }

    override fun favoriteAlbums(): Flow<List<AlbumRef>> =
        dao.observeByType(ALBUM).map { rows -> rows.mapNotNull { TrackJson.decodeAlbumOrNull(it.json) } }

    override fun favoriteArtists(): Flow<List<ArtistRef>> =
        dao.observeByType(ARTIST).map { rows -> rows.mapNotNull { TrackJson.decodeArtistOrNull(it.json) } }

    override fun isFavoriteTrack(source: ProviderRef): Flow<Boolean> =
        dao.observeExists(TRACK, source.provider, source.id)

    override suspend fun addTrack(track: Track) = add(
        entity(TRACK, track.source, TrackJson.encodeTrack(PortableTrackSanitizer.sanitize(track))),
    )

    override suspend fun removeTrack(source: ProviderRef) = remove(TRACK, source)

    override suspend fun toggleTrack(track: Track): Boolean {
        val nowFavorite = !dao.exists(TRACK, track.source.provider, track.source.id)
        if (nowFavorite) addTrack(track) else removeTrack(track.source)
        return nowFavorite
    }

    override suspend fun addAlbum(album: AlbumRef) =
        add(entity(ALBUM, album.source, TrackJson.encodeAlbum(album)))

    override suspend fun removeAlbum(source: ProviderRef) = remove(ALBUM, source)

    override suspend fun addArtist(artist: ArtistRef) =
        add(entity(ARTIST, artist.source, TrackJson.encodeArtist(artist)))

    override suspend fun removeArtist(source: ProviderRef) = remove(ARTIST, source)

    private suspend fun add(entity: FavoriteEntity) {
        val now = nowIso()
        dao.insertWithJournal(entity, journal(entity.type, entity.provider, entity.sourceId, "UPSERT", json.encodeToString(FavoriteEntity.serializer(), entity), now))
    }

    private suspend fun remove(type: String, source: ProviderRef) {
        val now = nowIso()
        dao.deleteWithJournal(type, source.provider, source.id, journal(type, source.provider, source.id, "DELETE", null, now))
    }

    private fun journal(type: String, provider: String, sourceId: String, operation: String, payload: String?, now: String) =
        SyncOutboxEntity(newId(), "FAVORITE", "$type:$provider:$sourceId", operation, payload, now)

    private fun entity(type: String, source: ProviderRef, json: String) =
        FavoriteEntity(type = type, provider = source.provider, sourceId = source.id, json = json, addedAtIso = nowIso())

    private companion object {
        const val TRACK = "TRACK"
        const val ALBUM = "ALBUM"
        const val ARTIST = "ARTIST"
    }
}
