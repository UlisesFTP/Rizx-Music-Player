package fm.rizx.player.data.search

import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.remote.soundcloud.toSoundcloudTrackOrNull
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toTrackOrNull
import fm.rizx.player.domain.model.SearchResults
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The Search "Underground" tab: songs straight from **YouTube** and **SoundCloud** via NewPipe — remixes,
 * edits, bootlegs and exclusives on YouTube, and indie/emerging artists on SoundCloud that the mainstream
 * catalogs (Deezer) don't carry.
 *
 * A data-layer orchestrator (mirrors `CanvasSource`): it drives the two NewPipe clients directly rather
 * than through a metadata provider, so it lives in `data`, not `domain`. Injected into the search ViewModel.
 */
interface StreamingSourcesSearch {
    suspend fun search(query: String): SearchResults
}

class NewPipeStreamingSourcesSearch(
    private val youtube: YoutubeExtractorClient,
    private val soundcloud: SoundcloudExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StreamingSourcesSearch {

    /**
     * Both sources run in parallel and **degrade independently**: a failing or unreachable source returns
     * nothing while the other still shows. YouTube uses a *plain* video search (not the "Music songs"
     * filter) precisely because that surfaces the unofficial remixes/edits; SoundCloud searches tracks.
     * Results are immediately playable — each track keeps its own `ProviderRef` and resolves against its
     * native provider.
     */
    override suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults()
        return coroutineScope {
            val yt = async(io) {
                runCatching { youtube.searchVideos(q, LIMIT).mapNotNull { it.toTrackOrNull() } }.getOrDefault(emptyList())
            }
            val sc = async(io) {
                runCatching { soundcloud.searchTracks(q, LIMIT).mapNotNull { it.toSoundcloudTrackOrNull() } }.getOrDefault(emptyList())
            }
            // YouTube first, then SoundCloud — the screen groups them under their own headings by source.
            SearchResults(tracks = yt.await() + sc.await())
        }
    }

    private companion object {
        const val LIMIT = 15
    }
}
