package fm.rizx.player.data.remote.youtube

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** A YouTube / YouTube Music playlist with **all** of its items (pagination already followed). */
data class YoutubePlaylistData(
    val name: String?,
    val uploaderName: String?,
    val items: List<StreamInfoItem>,
    /** True when the playlist was longer than the import bounds and we stopped early. */
    val truncated: Boolean = false,
)

/**
 * Thin, mockable seam over NewPipeExtractor's static API (mirrors `AudiusHostProvider`'s role): the
 * provider depends on this interface so it can be unit-tested without the real extractor. All methods
 * are **blocking** (NewPipe does synchronous network) — callers must dispatch to IO.
 */
interface YoutubeExtractorClient {
    /** Song candidates for a free-text `"artist title"` query (blocking network). */
    fun searchSongs(query: String, limit: Int): List<StreamInfoItem>

    /**
     * Plain YouTube video results for a free-text query (blocking network) — **not** YouTube Music.
     *
     * [searchSongs] filters to Music "songs", which is right for audio and wrong for pictures: Music
     * songs are overwhelmingly auto-generated *topic* uploads whose "video" is the square cover art
     * standing still. A plain search surfaces the artist's actual music video, which is the only thing
     * that makes the Now Playing canvas worth its bytes.
     */
    fun searchVideos(query: String, limit: Int): List<StreamInfoItem>

    /** Full stream info (incl. audio streams) for a YouTube watch URL (blocking network). */
    fun streamInfo(videoUrl: String): StreamInfo

    /** Playlist results for a free-text query (blocking network) — the Search "Playlists" tab. */
    fun searchPlaylists(query: String, limit: Int): List<PlaylistInfoItem>

    /**
     * The whole playlist at [playlistUrl] — `youtube.com/playlist?list=…`, `music.youtube.com/…`, or a
     * `watch?v=…&list=…` link (blocking network). **Follows pagination**: YouTube serves ~100 items per
     * page, so a single page would silently truncate a long playlist.
     */
    fun playlist(playlistUrl: String): YoutubePlaylistData
}

/** Real client: lazily one-time-inits NewPipe with our OkHttp-backed downloader, then delegates. */
class NewPipeYoutubeExtractorClient(
    private val downloader: NewPipeDownloaderImpl,
) : YoutubeExtractorClient {

    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(downloader)
                initialized = true
            }
        }
    }

    override fun searchSongs(query: String, limit: Int): List<StreamInfoItem> {
        ensureInit()
        val service = ServiceList.YouTube
        // Prefer YouTube Music "songs"; fall back to a plain search when Music returns nothing.
        val songs = runSearch(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        val items = songs.ifEmpty { runSearch(query, null) }
        return items.take(limit)
    }

    override fun searchVideos(query: String, limit: Int): List<StreamInfoItem> {
        ensureInit()
        return runSearch(query, null).take(limit)
    }

    private fun runSearch(query: String, contentFilter: String?): List<StreamInfoItem> {
        val service = ServiceList.YouTube
        val handler = if (contentFilter != null) {
            service.searchQHFactory.fromQuery(query, listOf(contentFilter), "")
        } else {
            service.searchQHFactory.fromQuery(query)
        }
        val info = SearchInfo.getInfo(service, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>()
    }

    override fun searchPlaylists(query: String, limit: Int): List<PlaylistInfoItem> {
        ensureInit()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "")
        val info = SearchInfo.getInfo(service, handler)
        return info.relatedItems.filterIsInstance<PlaylistInfoItem>().take(limit)
    }

    override fun streamInfo(videoUrl: String): StreamInfo {
        ensureInit()
        return StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
    }

    override fun playlist(playlistUrl: String): YoutubePlaylistData {
        ensureInit()
        val service = ServiceList.YouTube
        val info = PlaylistInfo.getInfo(service, playlistUrl)
        val items = mutableListOf<StreamInfoItem>()
        items += info.relatedItems

        // Page through the rest — `relatedItems` is only the first ~100. Bounded so a pathological
        // playlist can't loop or pull forever.
        //
        // Pagination is wrapped because NewPipe currently NPEs on YouTube continuation pages
        // (TeamNewPipe/NewPipe#13593: `getPlaylistHeader()` is null past the first page, and
        // `isCoursePlaylist` dereferences it). There is no fixed release. Rather than throw away the
        // ~100 tracks the first page already gave us, we stop at the failure and mark the result
        // truncated — a 40-track playlist imports whole, a 300-track one imports its first 100.
        var page: Page? = info.nextPage
        var pages = 1
        var stoppedEarly = false
        while (page != null && pages < MAX_PAGES && items.size < MAX_ITEMS) {
            val more = try {
                PlaylistInfo.getMoreItems(service, playlistUrl, page)
            } catch (e: Exception) {
                stoppedEarly = true // NewPipe's continuation bug — keep the pages we already have
                break
            }
            items += more.items
            page = more.nextPage
            pages++
        }
        return YoutubePlaylistData(
            name = info.name,
            uploaderName = info.uploaderName,
            items = items.take(MAX_ITEMS),
            truncated = stoppedEarly || page != null || items.size > MAX_ITEMS,
        )
    }

    private companion object {
        /** Import bounds: ~100 items per page, so this covers playlists up to [MAX_ITEMS] tracks. */
        const val MAX_PAGES = 20
        const val MAX_ITEMS = 2_000
    }
}
