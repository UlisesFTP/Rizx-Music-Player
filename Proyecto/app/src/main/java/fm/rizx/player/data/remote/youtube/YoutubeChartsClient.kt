package fm.rizx.player.data.remote.youtube

import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** What one chart page yields: the country's top songs and top artists. */
data class YoutubeChartsPage(
    val tracks: List<Track> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
)

/**
 * Reads YouTube's public music charts. See [YoutubeChartsResponse] for why this is keyless and why it
 * is `charts.youtube.com` rather than `music.youtube.com`.
 *
 * Deliberately **not** routed through NewPipeExtractor: its chart kiosks (`Trending`,
 * `trending_music`) build query parameters that the endpoint rejects with 400 today, so going through
 * it would buy a dependency on stale params instead of removing one. This is a plain POST on the
 * OkHttp client the app already has — no new dependency, and it inherits the app's DNS guard.
 */
class YoutubeChartsClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Overridden by tests to point at a local server. */
    private val endpoint: String = ENDPOINT,
) {

    /**
     * The chart for [countryCode] (ISO-3166 alpha-2), or the global chart when it is null.
     *
     * Null is the **unconsented** path, not a failure: the country only ever travels once the user has
     * agreed to regional recommendations, exactly as the Spotify and Apple chart sources already work.
     */
    suspend fun charts(countryCode: String?): YoutubeChartsPage = withContext(io) {
        // Two letters and nothing else: the code is interpolated into the request body, so anything
        // else is refused rather than escaped. `RegionResolver` already guarantees the shape, but this
        // is the boundary and it does not get to assume its caller.
        val cc = countryCode?.trim()
            ?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?.lowercase()
            ?: GLOBAL
        val request = Request.Builder()
            .url(endpoint)
            .post(body(cc).toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("empty body")
            // 280-380 KB in the wild. Bounded like every other import reader, so a hostile length can
            // never be allocated.
            if (source.request(MAX_BYTES + 1L)) throw IOException("charts response too large")
            parse(json.decodeFromString(YoutubeChartsResponse.serializer(), source.readUtf8()))
        }
    }

    /**
     * The two country fields are **not** the same field twice.
     *
     * `chart_params_country_code` selects the chart and does accept `global`; `gl` is the client's own
     * locale and must be a real country — sending `gl=GLOBAL` is a flat **HTTP 400**. Getting this
     * wrong would have broken precisely the default path, since the global chart is what an
     * unconsented install asks for. [NEUTRAL_GL] stands in there: a fixed storefront that says nothing
     * about the listener, the same stand-in the Apple chart source already uses for the same reason.
     */
    private fun body(countryCode: String): String {
        val gl = if (countryCode == GLOBAL) NEUTRAL_GL else countryCode.uppercase()
        return """{"context":{"client":{"clientName":"$CLIENT_NAME","clientVersion":"$CLIENT_VERSION",""" +
            """"hl":"en","gl":"$gl","experimentIds":[],"theme":"MUSIC"}},""" +
            """"browseId":"$BROWSE_ID",""" +
            """"query":"perspective=CHART_HOME&chart_params_country_code=$countryCode"}"""
    }

    private fun parse(response: YoutubeChartsResponse): YoutubeChartsPage {
        val content = response.contents?.sectionListRenderer?.contents
            ?.firstNotNullOfOrNull { it.musicAnalyticsSectionRenderer?.content }
            ?: return YoutubeChartsPage()
        return YoutubeChartsPage(
            tracks = content.trackTypes
                .firstOrNull { it.listType == TOP_VIEWS }
                ?.trackViews.orEmpty()
                .mapNotNull { it.toTrackOrNull() },
            artists = content.artists
                .firstOrNull { it.listType == TOP_VIEWS }
                ?.artistViews.orEmpty()
                .mapNotNull { it.toArtistRefOrNull() },
        )
    }

    private companion object {
        const val ENDPOINT = "https://charts.youtube.com/youtubei/v1/browse?alt=json&prettyPrint=false"
        const val BROWSE_ID = "FEmusic_analytics_charts_home"

        /** The chart page's own client identity. Public, and the endpoint asks for nothing else. */
        const val CLIENT_NAME = "WEB_MUSIC_ANALYTICS"
        const val CLIENT_VERSION = "2.0"

        const val GLOBAL = "global"

        /** The locale sent with the global chart. A fixed storefront, never the listener's own. */
        const val NEUTRAL_GL = "US"

        /** The songs/artists charts. The response also carries `TOP_SHORTS_BY_USAGE`, which is not music. */
        const val TOP_VIEWS = "TOP_VIEWS_CHART"

        const val MAX_BYTES = 8L * 1024 * 1024
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * A charted song → domain [Track], keeping its **exact video id** as identity so it plays that video
 * instead of being re-searched by title (the same rule the playlist importer follows).
 *
 * The chart's own thumbnail is kept as a placeholder rather than dropped: it renders something while
 * `TrackArtworkEnricher` upgrades it to the real cover, and an empty tile reads as a broken feed.
 */
internal fun YoutubeChartTrack.toTrackOrNull(): Track? {
    val videoId = encryptedVideoId?.takeIf { isYoutubeVideoId(it) } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    return Track(
        title = name,
        artists = artists.mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
            .map { ArtistCredit(name = it) },
        artwork = thumbnail.toArtworkSet(),
        source = ProviderRef(YoutubeIds.STREAMING, videoId, youtubeWatchUrl(videoId)),
    )
}

/** A charted artist → [ArtistRef]. The channel id is the only stable identity the chart offers. */
internal fun YoutubeChartArtist.toArtistRefOrNull(): ArtistRef? {
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    val channelId = externalChannelId?.takeIf { it.isNotBlank() } ?: return null
    return ArtistRef(
        name = name,
        artwork = thumbnail.toArtworkSet(),
        source = ProviderRef(YoutubeIds.STREAMING, "artist:$channelId", youtubeChannelUrl(channelId)),
    )
}

/** Null (not an empty set) when nothing is usable, so callers treat "no image" as one null check. */
private fun YoutubeChartThumbnails?.toArtworkSet(): ArtworkSet? {
    val items = this?.thumbnails.orEmpty().mapNotNull { image ->
        val url = image.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Artwork(url = url, width = image.width, height = image.height, purpose = ArtworkPurpose.COVER)
    }
    return items.takeIf { it.isNotEmpty() }?.let { ArtworkSet(it) }
}

internal fun youtubeChannelUrl(channelId: String): String = "https://www.youtube.com/channel/$channelId"
