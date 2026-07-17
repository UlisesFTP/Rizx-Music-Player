package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.repository.MetadataRepository
import javax.inject.Inject

/** Searches music by free-text query through the active metadata provider. */
class SearchMusicUseCase @Inject constructor(
    private val metadataRepository: MetadataRepository,
) {
    /** [types] narrows the search to specific categories (e.g. artists-only for the Artists tab); null = the provider default. */
    suspend operator fun invoke(query: String, types: List<SearchCategory>? = null): SearchResults =
        metadataRepository.search(SearchParams(query = query.trim(), types = types))
}
