package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.ItunesIds
import fm.rizx.player.data.remote.itunes.toSearchResults
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * First **real** metadata provider — the iTunes Search API. Unified search over songs; artist/album
 * sections are derived from the song rows. Network I/O runs on [io]; every failure is wrapped in a
 * typed [AppError] so the repository/ViewModel surface an error state instead of crashing (ADR 0006).
 * DTOs never escape [ItunesApi]/the mappers.
 */
class ItunesMetadataProvider(
    private val api: ItunesApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MetadataProvider {

    override val id: String = ItunesIds.METADATA
    override val kind: ProviderKind = ProviderKind.METADATA
    override val name: String = "iTunes Search"
    override val searchCapabilities: Set<SearchCapability> = setOf(SearchCapability.UNIFIED)

    override suspend fun search(params: SearchParams): SearchResults {
        val query = params.query.trim()
        if (query.isEmpty()) return SearchResults()
        return try {
            withContext(io) {
                api.search(term = query, limit = params.limit ?: DEFAULT_LIMIT).results.toSearchResults()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "search failed", e)
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 25
    }
}
