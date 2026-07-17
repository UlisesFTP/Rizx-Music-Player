package fm.rizx.player.data.provider

import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.delay

/**
 * Development metadata provider that searches the in-memory [FakeCatalog]. A short [delay] simulates
 * network latency so the UI's loading state is observable. Replaced by real providers in Phase 13.
 */
class FakeMetadataProvider : MetadataProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.METADATA
    override val name: String = "Fake Library"
    override val searchCapabilities: Set<SearchCapability> = setOf(SearchCapability.UNIFIED)

    override suspend fun search(params: SearchParams): SearchResults {
        delay(250) // simulate latency
        val q = params.query.trim().lowercase()
        if (q.isEmpty()) return SearchResults()
        return SearchResults(
            tracks = FakeCatalog.tracks.filter { it.matches(q) },
            artists = FakeCatalog.artists.filter { it.name.lowercase().contains(q) },
            albums = FakeCatalog.albums.filter { album ->
                album.title.lowercase().contains(q) ||
                    album.artists.any { it.name.lowercase().contains(q) }
            },
        )
    }

    private fun Track.matches(q: String): Boolean =
        title.lowercase().contains(q) ||
            artists.any { it.name.lowercase().contains(q) } ||
            album?.title?.lowercase()?.contains(q) == true

    companion object {
        const val ID = "fake-metadata"
    }
}
