package fm.rizx.player.data.repository

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.repository.NoMetadataProviderException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRepositoryTest {

    private class FakeMeta(
        override val id: String,
        private val result: SearchResults,
    ) : MetadataProvider {
        override val kind = ProviderKind.METADATA
        override val name = id
        override val searchCapabilities = setOf(SearchCapability.UNIFIED)
        var calls = 0
        override suspend fun search(params: SearchParams): SearchResults {
            calls++
            return result
        }
    }

    private fun resultsOf(title: String) =
        SearchResults(tracks = listOf(Track(title = title, source = ProviderRef("fake", title))))

    @Test
    fun `searches through the active provider`() = runTest {
        val registry = DefaultProviderRegistry()
        val provider = FakeMeta("m1", resultsOf("Velvet Hours"))
        registry.register(provider)
        val repo = MetadataRepositoryImpl(registry)

        val result = repo.search(SearchParams("velvet"))

        assertEquals(1, provider.calls)
        assertEquals("Velvet Hours", result.tracks.single().title)
    }

    @Test
    fun `uses whichever provider is active`() = runTest {
        val registry = DefaultProviderRegistry()
        registry.register(FakeMeta("m1", resultsOf("from-1"))) // first-wins active
        registry.register(FakeMeta("m2", resultsOf("from-2")))
        val repo = MetadataRepositoryImpl(registry)

        assertEquals("from-1", repo.search(SearchParams("x")).tracks.single().title)
        registry.setActive(ProviderKind.METADATA, "m2")
        assertEquals("from-2", repo.search(SearchParams("x")).tracks.single().title)
    }

    @Test
    fun `albumDetail dispatches to the active provider`() = runTest {
        val album = Album(title = "Discovery", source = ProviderRef("deezer", "album:1"))
        val registry = DefaultProviderRegistry()
        registry.register(object : MetadataProvider {
            override val id = "d"
            override val kind = ProviderKind.METADATA
            override val name = "Deezer"
            override val searchCapabilities = setOf(SearchCapability.UNIFIED)
            override val detailCapabilities = setOf(DetailCapability.ALBUM_DETAIL)
            override suspend fun search(params: SearchParams) = SearchResults()
            override suspend fun albumDetail(source: ProviderRef) = album
        })

        assertEquals("Discovery", MetadataRepositoryImpl(registry).albumDetail(ProviderRef("deezer", "album:1"))?.title)
    }

    @Test
    fun `albumDetail returns null when the active provider lacks the capability`() = runTest {
        val registry = DefaultProviderRegistry().apply { register(FakeMeta("m1", resultsOf("x"))) }

        assertNull(MetadataRepositoryImpl(registry).albumDetail(ProviderRef("x", "album:1")))
    }

    @Test
    fun `throws when no metadata provider is active`() = runTest {
        val repo = MetadataRepositoryImpl(DefaultProviderRegistry())
        var thrown = false
        try {
            repo.search(SearchParams("x"))
        } catch (e: NoMetadataProviderException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
