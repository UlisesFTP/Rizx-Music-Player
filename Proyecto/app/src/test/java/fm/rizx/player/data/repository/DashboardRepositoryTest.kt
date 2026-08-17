package fm.rizx.player.data.repository

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.GenreFeed
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRepositoryTest {

    private class FakeEnabled(private val disabled: Set<String> = emptySet()) : EnabledProviderStore {
        override fun isEnabled(id: String) = flowOf(id !in disabled)
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun snapshot(ids: Collection<String>) = ids.associateWith { it !in disabled }
    }

    private class FakeDash(
        override val id: String,
        private val tracks: List<Track> = emptyList(),
        private val failTracks: Boolean = false,
        /** Simulates a source that hangs — the reason [DashboardRepositoryImpl.safe] has a timeout. */
        private val stallMs: Long = 0L,
        private val stations: List<MoodStation> = emptyList(),
        private val stationTracks: Map<String, List<Track>> = emptyMap(),
        private val genres: Map<String, GenreFeed> = emptyMap(),
        private val knowsGenres: Boolean = genres.isNotEmpty(),
        private val failGenres: Boolean = false,
    ) : DashboardProvider {
        override val kind = ProviderKind.DASHBOARD
        override val name = id
        override val dashboardCapabilities = buildSet {
            add(DashboardCapability.TOP_TRACKS)
            if (stations.isNotEmpty()) add(DashboardCapability.MOOD_STATIONS)
            if (knowsGenres) add(DashboardCapability.GENRE_FEED)
        }
        override suspend fun topTracks(limit: Int): List<Track> {
            if (stallMs > 0) kotlinx.coroutines.delay(stallMs)
            return if (failTracks) throw RuntimeException("boom") else tracks
        }
        override suspend fun moodStations(limit: Int): List<MoodStation> = stations.take(limit)
        override suspend fun stationTracks(stationId: String, limit: Int): List<Track> =
            stationTracks[stationId] ?: throw RuntimeException("unknown station")
        override suspend fun genreFeed(genreId: String, limit: Int): GenreFeed =
            if (failGenres) throw RuntimeException("boom") else genres[genreId] ?: GenreFeed()
    }

    private fun track(title: String) = Track(title = title, source = ProviderRef("deezer", title))

    @Test
    fun `a hanging source is dropped instead of holding the whole feed`() = runTest {
        // `runTest` skips virtual delay, so the stall is an hour of *virtual* time — far past the
        // timeout, and instant to run. Before that timeout existed this test would simply never
        // return: every section is awaited before the feed is handed back and before it is cached, so
        // one hung source froze Home for every other platform too.
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("slow", tracks = listOf(track("A")), stallMs = 60 * 60_000L))
            register(FakeDash("fast", tracks = listOf(track("B"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        assertEquals(listOf("fast"), feed.topTracks.map { it.providerId })
    }

    @Test
    fun `the active source ids follow the on-off toggles`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("d1", tracks = listOf(track("A"))))
            register(FakeDash("d2", tracks = listOf(track("B"))))
        }

        val repo = DashboardRepositoryImpl(registry, FakeEnabled(disabled = setOf("d2")))

        // Home folds this into its cache key: a switched-off source has to change the key, or the
        // cached feed keeps serving that platform's charts for the rest of the cache's life.
        assertEquals(listOf("d1"), repo.activeSourceIds().first())
    }

    @Test
    fun `fans out over all registered dashboard providers with attribution`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("d1", tracks = listOf(track("A"))))
            register(FakeDash("d2", tracks = listOf(track("B"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        assertEquals(2, feed.topTracks.size)
        assertEquals(setOf("d1", "d2"), feed.topTracks.map { it.providerId }.toSet())
        assertEquals(listOf("A"), feed.topTracks.first { it.providerId == "d1" }.items.map { it.title })
    }

    @Test
    fun `a failing provider section is isolated and the rest survive`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("bad", failTracks = true))
            register(FakeDash("good", tracks = listOf(track("A"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        // The failing provider contributes nothing; the healthy one still appears.
        assertEquals(listOf("good"), feed.topTracks.map { it.providerId })
    }

    @Test
    fun `no dashboard providers yields an empty feed`() = runTest {
        val feed = DashboardRepositoryImpl(DefaultProviderRegistry(), FakeEnabled()).homeFeed()
        assertTrue(feed.isEmpty)
    }

    @Test
    fun `home requests the section specific feed depths`() = runTest {
        val requested = mutableMapOf<String, Int>()
        val provider = object : DashboardProvider {
            override val id = "limits"
            override val kind = ProviderKind.DASHBOARD
            override val name = id
            override val dashboardCapabilities = DashboardCapability.entries.toSet()
            override suspend fun topTracks(limit: Int): List<Track> { requested["tracks"] = limit; return emptyList() }
            override suspend fun topArtists(limit: Int): List<ArtistRef> { requested["artists"] = limit; return emptyList() }
            override suspend fun topAlbums(limit: Int): List<AlbumRef> { requested["albums"] = limit; return emptyList() }
            override suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> { requested["playlists"] = limit; return emptyList() }
            override suspend fun newReleases(limit: Int): List<AlbumRef> { requested["releases"] = limit; return emptyList() }
            override suspend fun moodStations(limit: Int): List<MoodStation> { requested["stations"] = limit; return emptyList() }
        }
        val registry = DefaultProviderRegistry().apply { register(provider) }

        DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        assertEquals(50, requested["tracks"])
        assertEquals(40, requested["artists"])
        assertEquals(40, requested["albums"])
        assertEquals(60, requested["playlists"])
        assertEquals(30, requested["releases"])
        assertEquals(100, requested["stations"])
    }

    @Test
    fun `a disabled provider is excluded from the fan-out`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("off", tracks = listOf(track("A"))))
            register(FakeDash("on", tracks = listOf(track("B"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled(disabled = setOf("off"))).homeFeed()

        assertEquals(listOf("on"), feed.topTracks.map { it.providerId })
    }

    @Test
    fun `stations ride the fan-out attributed to the provider that can resolve them`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("d1", stations = listOf(MoodStation("31061", "Pop"))))
            register(FakeDash("d2")) // no MOOD_STATIONS capability → contributes none
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        val result = feed.stations.single()
        assertEquals("d1", result.providerId)
        assertEquals(listOf("Pop"), result.items.map { it.title })
    }

    @Test
    fun `stationTracks routes to the named provider and degrades to empty otherwise`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(
                FakeDash(
                    "d1",
                    stations = listOf(MoodStation("31061", "Pop")),
                    stationTracks = mapOf("31061" to listOf(track("Abracadabra"))),
                ),
            )
        }
        val repo = DashboardRepositoryImpl(registry, FakeEnabled())

        assertEquals(listOf("Abracadabra"), repo.stationTracks("d1", "31061", 30).map { it.title })
        // Unknown provider → nobody can resolve it; a failing provider → same quiet empty.
        assertTrue(repo.stationTracks("nope", "31061", 30).isEmpty())
        assertTrue(repo.stationTracks("d1", "bad-id", 30).isEmpty())
    }

    // ---- Genre browse ----

    private fun genreFeed(vararg titles: String) = GenreFeed(tracks = titles.map(::track))

    /**
     * Genre ids belong to one catalogue, so this takes the first provider that recognises the id
     * rather than blending — a provider that doesn't own the id space answers empty and is skipped.
     */
    @Test
    fun `a genre feed comes from the first provider that knows the id`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("no-genres")) // no GENRE_FEED capability at all
            register(FakeDash("other-space", genres = mapOf("999" to genreFeed("Wrong"))))
            register(FakeDash("owner", genres = mapOf("464" to genreFeed("Master Of Puppets"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).genreFeed("464", 50)

        assertEquals(listOf("Master Of Puppets"), feed.tracks.map { it.title })
    }

    @Test
    fun `a provider that throws on a genre never breaks the browse`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("bad", knowsGenres = true, failGenres = true))
            register(FakeDash("good", genres = mapOf("464" to genreFeed("Toxicity"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).genreFeed("464", 50)

        assertEquals(listOf("Toxicity"), feed.tracks.map { it.title })
    }

    @Test
    fun `a disabled provider does not answer genre browsing either`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("off", genres = mapOf("464" to genreFeed("Hidden"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled(disabled = setOf("off"))).genreFeed("464", 50)

        assertTrue(feed.isEmpty)
    }

    // ---- Stations, as their own section ----

    @Test
    fun `moodStations fans out attributed, without building a whole feed`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("d1", stations = listOf(MoodStation("31061", "Pop"))))
            register(FakeDash("d2", stations = listOf(MoodStation("37121", "Chill Out"))))
            register(FakeDash("d3")) // no MOOD_STATIONS capability
        }

        val results = DashboardRepositoryImpl(registry, FakeEnabled()).moodStations(50)

        assertEquals(setOf("d1", "d2"), results.map { it.providerId }.toSet())
        assertEquals(listOf("Pop"), results.first { it.providerId == "d1" }.items.map { it.title })
    }

    @Test
    fun `a disabled provider contributes no stations to the see-all list either`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("off", stations = listOf(MoodStation("31061", "Pop"))))
            register(FakeDash("on", stations = listOf(MoodStation("37121", "Chill Out"))))
        }

        val results = DashboardRepositoryImpl(registry, FakeEnabled(disabled = setOf("off"))).moodStations(50)

        assertEquals(listOf("on"), results.map { it.providerId })
    }

    @Test
    fun `an unknown genre degrades to an empty feed`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("owner", genres = mapOf("464" to genreFeed("Toxicity"))))
        }

        assertTrue(DashboardRepositoryImpl(registry, FakeEnabled()).genreFeed("12345", 50).isEmpty)
    }
}
