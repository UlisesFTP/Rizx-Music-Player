package fm.rizx.player.data.provider

import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.youtube.YoutubeChartsClient
import fm.rizx.player.domain.model.coverUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * YouTube Music charts. The fixture is **trimmed from a real `charts.youtube.com` response**, keeping
 * its exact nesting (`contents.sectionListRenderer.contents[].musicAnalyticsSectionRenderer.content`)
 * and real field values — so a parser that only works against an invented shape fails here.
 */
class YoutubeChartsDashboardProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val settings = FakeSettingsRepository()

    private fun provider(country: String? = "mx") = YoutubeChartsDashboardProvider(
        client = YoutubeChartsClient(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; isLenient = true },
            io = Dispatchers.Unconfined,
            endpoint = server.url("/youtubei/v1/browse").toString(),
        ),
        region = RegionResolver(listOf({ country })),
        settings = settings,
    )

    private fun enqueueCharts() =
        server.enqueue(MockResponse().setResponseCode(200).setBody(FIXTURE))

    @Test
    fun `parses the real chart shape into tracks and artists`() = runBlocking {
        enqueueCharts()

        val tracks = provider().topTracks(10)

        // Non-empty is the assertion that matters most: this is exactly how the SoundCloud chart went
        // dark unnoticed — nothing threw, the row just quietly became empty.
        assertTrue(tracks.isNotEmpty())
        assertEquals(listOf("Todo Lo Fue", "Polvo Rosita"), tracks.map { it.title })
        assertEquals("Lenin Ramirez", tracks[0].artists.first().name)
    }

    @Test
    fun `a charted song keeps its video id as identity so it plays that video`() = runBlocking {
        enqueueCharts()

        val track = provider().topTracks(1).single()

        assertEquals("youtube:kwwdY1bp-Ck", track.source.identityKey)
        assertEquals("https://www.youtube.com/watch?v=kwwdY1bp-Ck", track.source.url)
        assertTrue(track.artwork.coverUrl()!!.startsWith("https://"))
    }

    @Test
    fun `the shorts chart is not a music chart and is ignored`() = runBlocking {
        enqueueCharts()

        // The fixture's TOP_SHORTS_BY_USAGE list comes first and would win a naive `first()`.
        assertTrue(provider().topTracks(10).none { it.title == "Not a song" })
    }

    @Test
    fun `top artists come back with their channel identity`() = runBlocking {
        enqueueCharts()

        val artists = provider().topArtists(10)

        assertEquals(listOf("Fuerza Regida", "Peso Pluma"), artists.map { it.name })
        assertTrue(artists[0].source.id.startsWith("artist:UC"))
    }

    @Test
    fun `without regional consent it asks for the global chart, not the country`() = runBlocking {
        settings.regionalConsentFlow.value = false
        enqueueCharts()

        provider(country = "mx").topTracks(1)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("chart_params_country_code=global"))
        assertTrue(!body.contains("chart_params_country_code=mx"))
        // `gl` is the client locale, not the chart selector, and it must stay a real country: the live
        // endpoint answers `gl=GLOBAL` with a flat 400, which would have killed exactly this — the
        // default, unconsented path — while every consented install kept working.
        assertTrue(body.contains(""""gl":"US""""))
        assertTrue(!body.contains(""""gl":"GLOBAL""""))
    }

    @Test
    fun `with consent the country travels`() = runBlocking {
        settings.regionalConsentFlow.value = true
        enqueueCharts()

        provider(country = "mx").topTracks(1)

        assertTrue(server.takeRequest().body.readUtf8().contains("chart_params_country_code=mx"))
    }

    @Test
    fun `both rows share one download`() = runBlocking {
        enqueueCharts()

        val p = provider()
        p.topTracks(5)
        p.topArtists(5)

        // Memoized: the second row must not re-fetch the page the first one already paid for.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an http failure degrades to an empty row instead of throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(emptyList<Any>(), provider().topTracks(5))
    }

    private companion object {
        /** Trimmed from a live `charts.youtube.com` response — nesting and values are real. */
        val FIXTURE = """
        {"contents":{"sectionListRenderer":{"contents":[{"musicAnalyticsSectionRenderer":{"content":{
          "trackTypes":[
            {"listType":"TOP_SHORTS_BY_USAGE","trackViews":[
              {"name":"Not a song","encryptedVideoId":"aaaaaaaaaaa"}]},
            {"listType":"TOP_VIEWS_CHART","trackViews":[
              {"id":"G:AJRuE4Ytoos","name":"Todo Lo Fue","viewCount":"7202079",
               "thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/qRB7=w180-h180","width":180,"height":180}]},
               "encryptedVideoId":"kwwdY1bp-Ck",
               "artists":[{"kgMid":"/g/11b7_vmtdr","name":"Lenin Ramirez"}]},
              {"id":"G:BKSvF5Zupp","name":"Polvo Rosita","viewCount":"5100000",
               "thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/aBcD=w180-h180","width":180,"height":180}]},
               "encryptedVideoId":"vTvGobaqo9E",
               "artists":[{"name":"Fuerza Regida"}]}]}],
          "artists":[{"listType":"TOP_VIEWS_CHART","artistViews":[
            {"id":"/g/11fy19q209","name":"Fuerza Regida","viewCount":"64529324",
             "thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/aguiu"}]},
             "externalChannelId":"UCFLI6KAjrKRtydD7KqiYvAw"},
            {"id":"/g/11h936h21j","name":"Peso Pluma","viewCount":"47016042",
             "thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/d-rW"}]},
             "externalChannelId":"UCLIB2ov9wbmLUgaWjE9nunA"}]}]
        }}}]}}}
        """.trimIndent()
    }
}
