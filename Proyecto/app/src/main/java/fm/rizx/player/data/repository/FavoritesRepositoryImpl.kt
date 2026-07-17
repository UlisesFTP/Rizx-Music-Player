package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.FavoriteDao
import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Room-backed [FavoritesRepository]. Identity is `(type, provider, sourceId)`; the DAO's `IGNORE`
 * insert makes re-adding idempotent (original `addedAtIso` kept). [nowIso] is injectable for tests.
 */
class FavoritesRepositoryImpl(
    private val dao: FavoriteDao,
    private val nowIso: () -> String = { Instant.now().toString() },
) : FavoritesRepository {

    override fun favoriteTracks(): Flow<List<Track>> =
        dao.observeByType(TRACK).map { rows -> rows.map { TrackJson.decodeTrack(it.json) } }

    override fun favoriteAlbums(): Flow<List<AlbumRef>> =
        dao.observeByType(ALBUM).map { rows -> rows.map { TrackJson.decodeAlbum(it.json) } }

    override fun favoriteArtists(): Flow<List<ArtistRef>> =
        dao.observeByType(ARTIST).map { rows -> rows.map { TrackJson.decodeArtist(it.json) } }

    override fun isFavoriteTrack(source: ProviderRef): Flow<Boolean> =
        dao.observeExists(TRACK, source.provider, source.id)

    override suspend fun addTrack(track: Track) =
        dao.insert(entity(TRACK, track.source, TrackJson.encodeTrack(track)))

    override suspend fun removeTrack(source: ProviderRef) = dao.delete(TRACK, source.provider, source.id)

    override suspend fun toggleTrack(track: Track): Boolean {
        val nowFavorite = !dao.exists(TRACK, track.source.provider, track.source.id)
        if (nowFavorite) addTrack(track) else removeTrack(track.source)
        return nowFavorite
    }

    override suspend fun addAlbum(album: AlbumRef) =
        dao.insert(entity(ALBUM, album.source, TrackJson.encodeAlbum(album)))

    override suspend fun removeAlbum(source: ProviderRef) = dao.delete(ALBUM, source.provider, source.id)

    override suspend fun addArtist(artist: ArtistRef) =
        dao.insert(entity(ARTIST, artist.source, TrackJson.encodeArtist(artist)))

    override suspend fun removeArtist(source: ProviderRef) = dao.delete(ARTIST, source.provider, source.id)

    private fun entity(type: String, source: ProviderRef, json: String) =
        FavoriteEntity(type = type, provider = source.provider, sourceId = source.id, json = json, addedAtIso = nowIso())

    private companion object {
        const val TRACK = "TRACK"
        const val ALBUM = "ALBUM"
        const val ARTIST = "ARTIST"
    }
}
