package fm.rizx.player.ui.library

import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.NoDownloads
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.DownloadRepository
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
        override suspend fun backfillArtwork(id: String) = Unit
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
            FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), playback, NoDownloads(), FakeSettingsRepository(),
        )
        backgroundScope.launch { vm.favoriteTracks.collect {} } // keep the WhileSubscribed StateFlow hot
        advanceUntilIdle()

        vm.playLiked(1)

        assertEquals(listOf("Velvet", "Ruby"), playback.lastContextTracks.map { it.title })
        assertEquals(1, playback.lastContextIndex)
        assertEquals(fm.rizx.player.domain.model.QueueSourceKind.LIKED, playback.lastContext?.kind)
    }

    @Test
    fun `playLiked queues the visible list when the tab is filtered`() = runTest {
        val playback = FakePlayback()
        val liked = listOf(track("Velvet"), track("Ruby"), track("Rust"))
        val vm = LibraryViewModel(
            FakeFavorites(liked),
            FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), playback, NoDownloads(), FakeSettingsRepository(),
        )
        backgroundScope.launch { vm.favoriteTracks.collect {} }
        advanceUntilIdle()

        // What the screen shows after filtering to "ru" — the index counts into *that* list.
        val visible = listOf(liked[1], liked[2])
        vm.playLiked(0, visible)

        assertEquals(listOf("Ruby", "Rust"), playback.lastContextTracks.map { it.title })
        assertEquals(0, playback.lastContextIndex)
    }

    @Test
    fun `playing an empty list does nothing`() = runTest {
        val playback = FakePlayback()
        val vm = LibraryViewModel(FakeFavorites(), FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), playback, NoDownloads(), FakeSettingsRepository())

        vm.playLiked(0, emptyList())
        vm.playRecent(0, emptyList())
        vm.playDownloads(0, emptyList())

        assertEquals(-1, playback.lastContextIndex)
        assertTrue(playback.lastContextTracks.isEmpty())
    }

    @Test
    fun `createPlaylist delegates a trimmed name`() = runTest {
        val playlists = FakePlaylists()
        val vm = LibraryViewModel(FakeFavorites(), playlists, FakeRecent(), InMemoryQueueRepository(), FakePlayback(), NoDownloads(), FakeSettingsRepository())

        vm.createPlaylist("  My Mix  ")
        advanceUntilIdle()

        assertEquals(listOf("My Mix"), playlists.created)
    }

    @Test
    fun `blank playlist name is ignored`() = runTest {
        val playlists = FakePlaylists()
        val vm = LibraryViewModel(FakeFavorites(), playlists, FakeRecent(), InMemoryQueueRepository(), FakePlayback(), NoDownloads(), FakeSettingsRepository())

        vm.createPlaylist("   ")
        advanceUntilIdle()

        assertTrue(playlists.created.isEmpty())
    }

    @Test
    fun `unfavoriteTrack removes by provider ref`() = runTest {
        val favorites = FakeFavorites()
        val vm = LibraryViewModel(favorites, FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), FakePlayback(), NoDownloads(), FakeSettingsRepository())
        val song = track("Velvet")

        vm.unfavoriteTrack(song)
        advanceUntilIdle()

        assertEquals(listOf(song.source), favorites.removed)
    }

    // ---- saving downloads to the phone ----

    /** A download repository whose state the test drives, and that records what was published. */
    private class FakeDownloads(
        entries: List<DownloadedTrack> = emptyList(),
        states: Map<String, DownloadState> = emptyMap(),
    ) : DownloadRepository {
        val exported = mutableListOf<String>()
        override val downloads = MutableStateFlow(entries)
        override val states = MutableStateFlow(states)
        override fun localStream(track: Track): Stream? = null
        override fun download(track: Track, format: DownloadFormat?) = Unit
        override fun downloadAll(tracks: List<Track>) = Unit
        override fun cancel(key: String) = Unit
        override suspend fun delete(key: String) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun markCorrupt(key: String) = Unit
        override suspend fun export(key: String): Result<String> {
            exported += key
            return Result.success("$key.m4a")
        }
    }

    private fun downloaded(title: String, onPhone: Boolean) = DownloadedTrack(
        track = track(title),
        fileName = "$title.m4a",
        sizeBytes = 1_000,
        container = "m4a",
        downloadedAtIso = "2026-08-03T10:00:00Z",
        exportedUri = if (onPhone) "content://media/audio/1" else null,
    )

    @Test
    fun `the question is put while a download is running, not before`() = runTest {
        val downloads = FakeDownloads()
        val vm = LibraryViewModel(FakeFavorites(), FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), FakePlayback(), downloads, FakeSettingsRepository())
        backgroundScope.launch { vm.askSaveToPhone.collect {} }
        advanceUntilIdle()

        assertTrue("nothing is downloading yet", !vm.askSaveToPhone.value)

        downloads.states.value = mapOf("deezer:1" to DownloadState(DownloadStatus.DOWNLOADING))
        advanceUntilIdle()

        assertTrue(vm.askSaveToPhone.value)
    }

    @Test
    fun `someone who already has downloads is not asked about them at launch`() = runTest {
        // A finished download is not a question: the user is asked about work they just started.
        val downloads = FakeDownloads(
            entries = listOf(downloaded("Velvet", onPhone = false)),
            states = mapOf("meta:Velvet" to DownloadState(DownloadStatus.COMPLETE)),
        )
        val vm = LibraryViewModel(FakeFavorites(), FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), FakePlayback(), downloads, FakeSettingsRepository())
        backgroundScope.launch { vm.askSaveToPhone.collect {} }
        advanceUntilIdle()

        assertTrue(!vm.askSaveToPhone.value)
    }

    @Test
    fun `once answered the question never comes back`() = runTest {
        val settings = FakeSettingsRepository()
        val downloads = FakeDownloads(states = mapOf("deezer:1" to DownloadState(DownloadStatus.DOWNLOADING)))
        val vm = LibraryViewModel(FakeFavorites(), FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), FakePlayback(), downloads, settings)
        backgroundScope.launch { vm.askSaveToPhone.collect {} }
        advanceUntilIdle()
        assertTrue(vm.askSaveToPhone.value)

        vm.setSaveToPhone(true)
        advanceUntilIdle()

        assertTrue(!vm.askSaveToPhone.value)
        assertEquals(true, settings.saveDownloadsToPhoneFlow.value)
    }

    @Test
    fun `saving everything skips the songs already on the phone`() = runTest {
        val downloads = FakeDownloads()
        val vm = LibraryViewModel(FakeFavorites(), FakePlaylists(), FakeRecent(), InMemoryQueueRepository(), FakePlayback(), downloads, FakeSettingsRepository())
        var saved = -1
        var failed = -1

        vm.exportDownloads(
            listOf(downloaded("Velvet", onPhone = true), downloaded("Ruby", onPhone = false)),
        ) { s, f -> saved = s; failed = f }
        advanceUntilIdle()

        assertEquals(listOf("meta:Ruby"), downloads.exported)
        assertEquals(1, saved)
        assertEquals(0, failed)
    }
}
