package fm.rizx.player.data.provider

import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.delay

/**
 * A second development metadata provider so the Sources screen has a real choice. It searches the same
 * [FakeCatalog] but returns **songs only** (no artist/album sections) — a visibly different source, so
 * switching the active provider is observable.
 */
class FakeMetadataProviderB : MetadataProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.METADATA
    override val name: String = "Rizx Local"
    override val searchCapabilities: Set<SearchCapability> = setOf(SearchCapability.UNIFIED)

    override suspend fun search(params: SearchParams): SearchResults {
        delay(180)
        val query = params.query.trim().lowercase()
        if (query.isEmpty()) return SearchResults()
        val tracks = FakeCatalog.tracks.filter { track ->
            track.title.lowercase().contains(query) ||
                track.artists.any { it.name.lowercase().contains(query) } ||
                track.album?.title?.lowercase()?.contains(query) == true
        }
        return SearchResults(tracks = tracks)
    }

    companion object {
        const val ID = "fake-metadata-b"
    }
}
