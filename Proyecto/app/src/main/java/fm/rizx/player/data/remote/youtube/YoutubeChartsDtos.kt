package fm.rizx.player.data.remote.youtube

import kotlinx.serialization.Serializable

/**
 * DTOs for YouTube's **public chart analytics** endpoint (`charts.youtube.com`), the data behind
 * charts.youtube.com's country pages.
 *
 * Keyless in the project's strict sense (ADR 0018): verified live to answer HTTP 200 with **no API key
 * at all**, no cookie, no visitor token and no browser-only header — an honest `Rizx/…` User-Agent is
 * enough. There is no access control here to defeat; the page is a public analytics dashboard.
 *
 * Deliberately **not** `music.youtube.com`'s `FEmusic_charts`: that one ships an empty song shelf and
 * ignores `gl` entirely (asking for US returned Mexico's playlists), so a feature built on it would
 * look right in one country and be wrong everywhere else.
 *
 * Shape: `contents…musicAnalyticsSectionRenderer.content` → `{ trackTypes[], artists[] }`. Only the
 * mapped fields are declared; the shared `Json` ignores the rest.
 */
@Serializable
data class YoutubeChartsResponse(val contents: YoutubeChartsContents? = null)

@Serializable
data class YoutubeChartsContents(
    val sectionListRenderer: YoutubeChartsSectionList? = null,
)

@Serializable
data class YoutubeChartsSectionList(val contents: List<YoutubeChartsSection> = emptyList())

@Serializable
data class YoutubeChartsSection(
    val musicAnalyticsSectionRenderer: YoutubeChartsAnalytics? = null,
)

@Serializable
data class YoutubeChartsAnalytics(val content: YoutubeChartsContent? = null)

@Serializable
data class YoutubeChartsContent(
    val trackTypes: List<YoutubeChartTrackList> = emptyList(),
    val artists: List<YoutubeChartArtistList> = emptyList(),
)

/**
 * One track chart. [listType] matters: the response also carries `TOP_SHORTS_BY_USAGE` lists, which are
 * Shorts sounds rather than songs — only `TOP_VIEWS_CHART` is a music chart.
 */
@Serializable
data class YoutubeChartTrackList(
    val listType: String? = null,
    val trackViews: List<YoutubeChartTrack> = emptyList(),
)

/**
 * One charted song. [encryptedVideoId] is not encrypted in any meaningful sense — it is the ordinary
 * 11-character video id (verified playable), so a charted track drops straight into the existing
 * NewPipe streaming path with its real identity rather than being re-searched by title.
 */
@Serializable
data class YoutubeChartTrack(
    val name: String? = null,
    val encryptedVideoId: String? = null,
    val thumbnail: YoutubeChartThumbnails? = null,
    val artists: List<YoutubeChartArtistCredit> = emptyList(),
)

@Serializable
data class YoutubeChartArtistCredit(val name: String? = null)

@Serializable
data class YoutubeChartArtistList(
    val listType: String? = null,
    val artistViews: List<YoutubeChartArtist> = emptyList(),
)

/** One charted artist. [externalChannelId] is the channel id — the only stable identity on offer. */
@Serializable
data class YoutubeChartArtist(
    val name: String? = null,
    val externalChannelId: String? = null,
    val thumbnail: YoutubeChartThumbnails? = null,
)

@Serializable
data class YoutubeChartThumbnails(val thumbnails: List<YoutubeChartImage> = emptyList())

@Serializable
data class YoutubeChartImage(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
