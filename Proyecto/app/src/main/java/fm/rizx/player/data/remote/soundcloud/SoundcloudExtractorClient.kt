package fm.rizx.player.data.remote.soundcloud

import fm.rizx.player.data.remote.youtube.NewPipeDownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
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

    /** User (artist) results for a free-text query — the Search "Artists" tab. Empty when unsupported. */
    fun searchUsers(query: String, limit: Int): List<ChannelInfoItem> = emptyList()

    /**
     * SoundCloud's own "related tracks" for a track permalink — the up-next engine. NewPipe reads
     * `api-v2.soundcloud.com/tracks/{id}/related` for us, so this stays keyless.
     */
    fun related(trackUrl: String, limit: Int): List<StreamInfoItem> = emptyList()

    /**
     * A SoundCloud chart kiosk ("Top 50" / "New & hot") for the Home feed. [kind] is the kiosk id;
     * NewPipe exposes them per-service, so an unknown kiosk simply yields nothing.
     */
    fun charts(kind: String, limit: Int): List<StreamInfoItem> = emptyList()
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

    override fun searchUsers(query: String, limit: Int): List<ChannelInfoItem> {
        ensureInit()
        val service = ServiceList.SoundCloud
        val handler = service.searchQHFactory.fromQuery(query, listOf(SoundcloudSearchQueryHandlerFactory.USERS), "")
        return SearchInfo.getInfo(service, handler).relatedItems.filterIsInstance<ChannelInfoItem>().take(limit)
    }

    override fun related(trackUrl: String, limit: Int): List<StreamInfoItem> {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.SoundCloud, trackUrl)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().take(limit)
    }

    override fun charts(kind: String, limit: Int): List<StreamInfoItem> {
        ensureInit()
        val service = ServiceList.SoundCloud
        val kiosks = service.kioskList
        // Ask for the named kiosk, else whatever this service calls its default — never throw on a
        // catalogue that renamed its charts.
        val handler = runCatching { kiosks.getListLinkHandlerFactoryByType(kind).fromId(kind) }.getOrNull()
            ?: kiosks.defaultKioskId.let { id -> kiosks.getListLinkHandlerFactoryByType(id).fromId(id) }
        val info = KioskInfo.getInfo(service, handler.url)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().take(limit)
    }
}
