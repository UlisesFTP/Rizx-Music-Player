package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Favorites in three categories (tracks, albums, artists), identified/deduped by
 * `source: ProviderRef` (`provider + id`; url/title excluded) — favorites from different providers
 * are distinct, and re-adding is idempotent, preserving the original timestamp (§7.1). All lists are
 * newest-first. Resolved stream URLs are never persisted.
 */
interface FavoritesRepository {

    fun favoriteTracks(): Flow<List<Track>>
    fun favoriteAlbums(): Flow<List<AlbumRef>>
    fun favoriteArtists(): Flow<List<ArtistRef>>

    fun isFavoriteTrack(source: ProviderRef): Flow<Boolean>

    suspend fun addTrack(track: Track)
    suspend fun removeTrack(source: ProviderRef)
    /** Adds or removes the track; returns the new favorite state. */
    suspend fun toggleTrack(track: Track): Boolean

    suspend fun addAlbum(album: AlbumRef)
    suspend fun removeAlbum(source: ProviderRef)

    suspend fun addArtist(artist: ArtistRef)
    suspend fun removeArtist(source: ProviderRef)
}
