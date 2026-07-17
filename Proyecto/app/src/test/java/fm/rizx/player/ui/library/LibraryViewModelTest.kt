package fm.rizx.player.ui.library

import fm.rizx.player.NoDownloads
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeFavorites(private val tracks: List<Track> = emptyList()) : FavoritesRepository {
        val removed = mutableListOf<ProviderRef>()
        override fun favoriteTracks(): Flow<List<Track>> = flowOf(tracks)
        override fun favoriteAlbums(): Flow<List<AlbumRef>> = flowOf(emptyList())
        override fun favoriteArtists(): Flow<List<ArtistRef>> = flowOf(emptyList())
        override fun isFavoriteTrack(source: ProviderRef): Flow<Boolean> = flowOf(false)
        override suspend fun addTrack(track: Track) {}
        override suspend fun removeTrack(source: ProviderRef) { removed += source }
        override suspend fun toggleTrack(track: Track): Boolean = true
        override suspend fun addAlbum(album: AlbumRef) {}
        override suspend fun removeAlbum(source: ProviderRef) {}
        override suspend fun addArtist(artist: ArtistRef) {}
        override suspend fun removeArtist(source: ProviderRef) {}
    }

    private class FakePlaylists : PlaylistRepository {
        val created = mutableListOf<String>()
        val addedTo = mutableListOf<Pair<String, List<Track>>>()
        override fun playlists(): Flow<List<PlaylistSummary>> = flowOf(emptyList())
        override fun playlist(id: String): Flow<Playlist?> = flowOf(null)
        override suspend fun createPlaylist(name: String, description: String?): String { created += name; return "id-${created.size}" }
        override suspend fun deletePlaylist(id: String) {}
        override suspend fun rename(id: String, name: String, description: String?) {}
        override suspend fun addTracks(playlistId: String, tracks: List<Track>) { addedTo += playlistId to tracks }
        override suspend fun removeItem(playlistId: String, itemId: String) {}
        override suspend fun reorder(playlistId: String, fromIndex: Int, toIndex: Int) {}
        override suspend fun saveQueueAsPlaylist(name: String, tracks: List<Track>): String { created += name; return "id" }
        override suspend fun exportPlaylist(id: String): String? = null
        override suspend fun importPlaylistFile(text: String, fallbackName: String?): String = "id"
        override suspend fun importFromUrl(url: String): String = "id"
        override suspend fun previewPlaylist(source: ProviderRef): List<Track> = emptyList()
    }

    private class FakeRecent : RecentlyPlayedRepository {
        override fun recent(limit: Int): Flow<List<Track>> = flowOf(emptyList())
        override suspend fun record(track: Track) {}
        override suspend fun clear() {}
    }

    private class FakePlayback : PlaybackController {
        var lastPlayed: String? = null
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueueItem(queueItemId: String) { lastPlayed = queueItemId }
        override fun playTrack(track: Track) {}
        var lastContext: fm.rizx.player.domain.model.QueueContext? = null
        var lastContextTracks: List<Track> = emptyList()
        var lastContextIndex: Int = -1
        override fun playContext(tracks: List<Track>, startIndex: Int, context: fm.rizx.player.domain.model.QueueContext) {
            lastContextTracks = tracks; lastContextIndex = startIndex; lastContext = context
        }
        override fun playRadio(track: Track) {}
        override fun play() {}
        override fun pause() {}
        override fun toggle() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun skipNext() {}
        override fun skipPrevious() {}
        override fun release() {}
    }

    private fun track(title: String) = Track(title = title, source = ProviderRef("meta", title))

    @Test
    fun `playLiked plays the liked list as the queue context from the tapped index`() = runTest {
        val playback = FakePlayback()
        val vm = LibraryViewModel(
            FakeFavorites(listOf(track("Velvet"), track("Ruby"))),
            FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), playback, NoDownloads(),
        )
        backgroundScope.launch { vm.favoriteTracks.collect {} } // keep the WhileSubscribed StateFlow hot
        advanceUntilIdle()

        vm.playLiked(1)

        assertEquals(listOf("Velvet", "Ruby"), playback.lastContextTracks.map { it.title })
        assertEquals(1, playback.lastContextIndex)
        assertEquals(fm.rizx.player.domain.model.QueueSourceKind.LIKED, playback.lastContext?.kind)
    }

    @Test
    fun `createPlaylist delegates a trimmed name`() = runTest {
        val playlists = FakePlaylists()
        val vm = LibraryViewModel(FakeFavorites(), playlists, FakeRecent(), InMemoryQueueRepository(), FakePlayback(), NoDownloads())

        vm.createPlaylist("  My Mix  ")
        advanceUntilIdle()

        assertEquals(listOf("My Mix"), playlists.created)
    }

    @Test
    fun `blank playlist name is ignored`() = runTest {
        val playlists = FakePlaylists()
        val vm = LibraryViewModel(FakeFavorites(), playlists, FakeRecent(), InMemoryQueueRepository(), FakePlayback(), NoDownloads())

        vm.createPlaylist("   ")
        advanceUntilIdle()

        assertTrue(playlists.created.isEmpty())
    }

    @Test
    fun `unfavoriteTrack removes by provider ref`() = runTest {
        val favorites = FakeFavorites()
        val vm = LibraryViewModel(favorites, FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), FakePlayback(), NoDownloads())
        val song = track("Velvet")

        vm.unfavoriteTrack(song)
        advanceUntilIdle()

        assertEquals(listOf(song.source), favorites.removed)
    }
}
