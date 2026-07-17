package fm.rizx.player.data.search

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.toPlaylistRef
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toPlaylistRefOrNull
import fm.rizx.player.domain.model.SearchResults
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The Search "Playlists" tab: playlists from **Deezer** and **YouTube** in one call. Spotify is absent
 * on purpose — its search needs a rotating TOTP-minted bearer that breaks this project's keyless rule
 * (verified: the anonymous-token endpoint is blocked); Spotify playlists are still importable by URL.
 *
 * A data-layer orchestrator (mirrors [StreamingSourcesSearch]): it drives the Deezer API + the NewPipe
 * YouTube client directly rather than through a metadata provider, so it lives in `data`. The refs it
 * returns keep each source's `ProviderRef`, so opening one reconstructs its URL and loads the exact
 * playlist through the existing playlist providers.
 */
interface PlaylistSourcesSearch {
    suspend fun search(query: String): SearchResults
}

class DefaultPlaylistSourcesSearch(
    private val deezer: DeezerApi,
    private val youtube: YoutubeExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistSourcesSearch {

    /**
     * Both sources run in parallel and **degrade independently**: a failing or unreachable source
     * contributes nothing while the other still shows. Deezer first, then YouTube — the screen groups
     * them under their own headings by source.
     */
    override suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults()
        return coroutineScope {
            val dz = async(io) {
                runCatching { deezer.searchPlaylists(q, LIMIT).data.mapNotNull { it.toPlaylistRef() } }.getOrDefault(emptyList())
            }
            val yt = async(io) {
                runCatching { youtube.searchPlaylists(q, LIMIT).mapNotNull { it.toPlaylistRefOrNull() } }.getOrDefault(emptyList())
            }
            SearchResults(playlists = dz.await() + yt.await())
        }
    }

    private companion object {
        const val LIMIT = 12
    }
}
