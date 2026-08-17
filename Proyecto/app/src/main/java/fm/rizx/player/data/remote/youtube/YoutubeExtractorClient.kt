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
    val thumbnails: List<org.schabi.newpipe.extractor.Image> = emptyList(),
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

    /** YouTube Music album rows. Empty by default so existing fake clients remain source-compatible. */
    fun searchMusicAlbums(query: String, limit: Int): List<PlaylistInfoItem> = emptyList()

    /** YouTube Music playlist rows. Empty by default so existing fake clients remain source-compatible. */
    fun searchMusicPlaylists(query: String, limit: Int): List<PlaylistInfoItem> = emptyList()

    /**
     * The whole playlist at [playlistUrl] — `youtube.com/playlist?list=…`, `music.youtube.com/…`, or a
     * `watch?v=…&list=…` link (blocking network). **Follows pagination**: YouTube serves ~100 items per
     * page, so a single page would silently truncate a long playlist.
     */
    fun playlist(playlistUrl: String): YoutubePlaylistData

    /**
     * The YouTube **Mix** (auto-generated radio) seeded by [videoId] — YT Music's own autoplay
     * recommendations (blocking network). Tries the music mix (`RDAMVM<id>`) first, then the standard
     * mix (`RD<id>`). Mixes only resolve through a **watch URL carrying the list param** (a bare
     * `playlist?list=RD…` URL is rejected upstream). First page only (~25 items) — each refill
     * re-seeds from the then-current track, exactly how YT's own autoplay evolves.
     */
    fun mix(videoId: String, limit: Int): List<StreamInfoItem>

    /**
     * Autocomplete for a partial query (blocking network) — what the search box offers while typing.
     * Defaults to empty so a client that doesn't do suggestions simply offers none.
     */
    fun suggestions(query: String, limit: Int): List<String> = emptyList()
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

    override fun searchMusicAlbums(query: String, limit: Int): List<PlaylistInfoItem> =
        runPlaylistSearch(query, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS, limit)

    override fun searchMusicPlaylists(query: String, limit: Int): List<PlaylistInfoItem> =
        runPlaylistSearch(query, YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS, limit)

    private fun runPlaylistSearch(query: String, filter: String, limit: Int): List<PlaylistInfoItem> {
        ensureInit()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        return SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<PlaylistInfoItem>()
            .take(limit)
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
        // Continuations used to NPE (TeamNewPipe/NewPipe#13593: `getPlaylistHeader()` null past the first
        // page), which silently capped every long playlist at ~100 tracks; NewPipeExtractor v0.26.4 fixes
        // it (#1518). The try/catch stays as the safety net it always should have been: a future
        // extractor regression degrades to a short import marked truncated, never a failed one.
        var page: Page? = info.nextPage
        var pages = 1
        var stoppedEarly = false
        while (page != null && pages < MAX_PAGES && items.size < MAX_ITEMS) {
            // One retry before giving up. A continuation is a live request to YouTube, so a single
            // dropped connection mid-playlist used to end the import silently — and the user saw a
            // round "100 tracks" that looked like a hard cap rather than the network blip it was.
            val more = try {
                PlaylistInfo.getMoreItems(service, playlistUrl, page)
            } catch (e: Exception) {
                try {
                    PlaylistInfo.getMoreItems(service, playlistUrl, page)
                } catch (retry: Exception) {
                    stoppedEarly = true // keep the pages we already have rather than losing the import
                    break
                }
            }
            items += more.items
            page = more.nextPage
            pages++
        }
        return YoutubePlaylistData(
            name = info.name,
            uploaderName = info.uploaderName,
            items = items.take(MAX_ITEMS),
            thumbnails = info.thumbnails,
            truncated = stoppedEarly || page != null || items.size > MAX_ITEMS,
        )
    }

    override fun mix(videoId: String, limit: Int): List<StreamInfoItem> {
        ensureInit()
        val service = ServiceList.YouTube
        for (listId in listOf("RDAMVM$videoId", "RD$videoId")) {
            val items = try {
                PlaylistInfo.getInfo(service, "${youtubeWatchUrl(videoId)}&list=$listId").relatedItems
            } catch (e: Exception) {
                emptyList()
            }
            if (items.isNotEmpty()) return items.take(limit)
        }
        return emptyList()
    }

    override fun suggestions(query: String, limit: Int): List<String> {
        ensureInit()
        // NewPipe already wraps Google's suggest endpoint — the same source the big apps' search boxes
        // use — so this needs no new API, key or dependency.
        return runCatching { ServiceList.YouTube.suggestionExtractor.suggestionList(query) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .take(limit)
    }

    private companion object {
        /**
         * Import bounds: ~100 items per page, so [MAX_PAGES] is sized to reach [MAX_ITEMS], which in turn
         * matches the repository's own save ceiling — this loop should never be the shorter limit of the
         * two, or a long playlist gets cut by a number the user is never shown.
         */
        const val MAX_PAGES = 120
        const val MAX_ITEMS = 10_000
    }
}
