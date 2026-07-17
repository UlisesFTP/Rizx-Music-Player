package fm.rizx.player.data.remote.soundcloud

import fm.rizx.player.data.remote.youtube.NewPipeDownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * NewPipe seam for **SoundCloud** — the same engine the YouTube client uses, pointed at
 * `ServiceList.SoundCloud`. Keyless from our side: NewPipe scrapes SoundCloud's `client_id` itself using
 * the shared [NewPipeDownloaderImpl]. Mockable interface for tests; all methods block (NewPipe is
 * synchronous) so callers must dispatch to IO.
 */
interface SoundcloudExtractorClient {
    /** SoundCloud track results for a free-text query (blocking network). */
    fun searchTracks(query: String, limit: Int): List<StreamInfoItem>

    /** Full stream info (incl. audio streams) for a SoundCloud track URL (blocking network). */
    fun streamInfo(trackUrl: String): StreamInfo
}

/** Real client: shares the OkHttp-backed downloader; `NewPipe.init` is process-global so one call arms all services. */
class NewPipeSoundcloudExtractorClient(
    private val downloader: NewPipeDownloaderImpl,
) : SoundcloudExtractorClient {

    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(downloader) // idempotent; the YouTube client may have armed it already
                initialized = true
            }
        }
    }

    override fun searchTracks(query: String, limit: Int): List<StreamInfoItem> {
        ensureInit()
        val service = ServiceList.SoundCloud
        // TRACKS, not ALL — a plain query also returns users/playlists, which we'd only filter out.
        val handler = service.searchQHFactory.fromQuery(query, listOf(SoundcloudSearchQueryHandlerFactory.TRACKS), "")
        val info = SearchInfo.getInfo(service, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().take(limit)
    }

    override fun streamInfo(trackUrl: String): StreamInfo {
        ensureInit()
        return StreamInfo.getInfo(ServiceList.SoundCloud, trackUrl)
    }
}
