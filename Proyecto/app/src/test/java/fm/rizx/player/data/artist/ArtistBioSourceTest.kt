package fm.rizx.player.data.artist

import fm.rizx.player.data.local.store.ArtistBioStore
import fm.rizx.player.data.remote.wikipedia.WikipediaApi
import fm.rizx.player.data.remote.wikipedia.WikipediaQueryDto
import fm.rizx.player.data.remote.wikipedia.WikipediaSearchDto
import fm.rizx.player.data.remote.wikipedia.WikipediaSearchRowDto
import fm.rizx.player.data.remote.wikipedia.WikipediaSummaryDto
import fm.rizx.player.data.remote.wikipedia.WikipediaUrlDto
import fm.rizx.player.data.remote.wikipedia.WikipediaUrlsDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistBioSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Records what was asked for, so "did it go to the network at all?" is testable. */
    private class FakeWikipedia(
        val titles: List<String> = emptyList(),
        val pages: Map<String, WikipediaSummaryDto> = emptyMap(),
        val failSearch: Boolean = false,
    ) : WikipediaApi {
        var searches = 0
        var summaries = 0

        override suspend fun search(url: String): WikipediaSearchDto {
            searches++
            if (failSearch) throw java.io.IOException("offline")
            return WikipediaSearchDto(WikipediaQueryDto(titles.map { WikipediaSearchRowDto(it) }))
        }

        override suspend fun summary(url: String): WikipediaSummaryDto {
            summaries++
            // Wikipedia's own behaviour: the last path segment is a percent-encoded, underscored title
            // — parentheses and all ("Ghost_%28Swedish_band%29").
            val title = java.net.URLDecoder.decode(url.substringAfterLast('/'), "UTF-8")
            return pages[title] ?: throw java.io.IOException("404")
        }
    }

    private fun page(
        title: String,
        description: String?,
        extract: String = "A paragraph about them.",
        type: String = "standard",
    ) = WikipediaSummaryDto(
        type = type,
        title = title,
        description = description,
        extract = extract,
        contentUrls = WikipediaUrlsDto(WikipediaUrlDto("https://en.wikipedia.org/wiki/${title.replace(' ', '_')}")),
    )

    private fun source(
        api: WikipediaApi,
        file: File = File(tmp.root, "bios.json"),
        now: Instant = Instant.parse("2026-07-30T12:00:00Z"),
    ) = WikipediaArtistBioSource(
        api = api,
        // Unconfined rather than a test dispatcher of its own: nothing here is about timing, and two
        // schedulers inside one `runTest` is an error, not a subtlety.
        store = ArtistBioStore(file, now = { now }, io = Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        language = { "en" },
        now = { now },
    )

    @Test
    fun `a musician's page is accepted`() = runTest {
        val api = FakeWikipedia(
            titles = listOf("Daft Punk"),
            pages = mapOf("Daft_Punk" to page("Daft Punk", "French electronic music duo")),
        )

        val bio = source(api).bioFor("deezer:artist:27", "Daft Punk")

        assertEquals("A paragraph about them.", bio?.text)
        assertEquals("https://en.wikipedia.org/wiki/Daft_Punk", bio?.sourceUrl)
    }

    @Test
    fun `a disambiguation page is never a biography`() = runTest {
        val api = FakeWikipedia(
            titles = listOf("Ghost"),
            pages = mapOf("Ghost" to page("Ghost", "band", type = "disambiguation")),
        )

        assertNull(source(api).bioFor("deezer:artist:1", "Ghost"))
    }

    @Test
    fun `a namesake who is not a musician is rejected`() = runTest {
        // The city of Chicago outranks the band in Wikipedia's own search.
        val api = FakeWikipedia(
            titles = listOf("Chicago"),
            pages = mapOf("Chicago" to page("Chicago", "Most populous city in Illinois, US")),
        )

        assertNull(source(api).bioFor("deezer:artist:2", "Chicago"))
    }

    @Test
    fun `the wrong namesake is passed over for the right one`() = runTest {
        // Wikipedia's own ranking for "Chicago": the city first, the band fourth.
        val api = FakeWikipedia(
            titles = listOf("Chicago", "Chicago Tribune", "Chicago (band)"),
            pages = mapOf(
                "Chicago" to page("Chicago", "Most populous city in Illinois, US", extract = "A city."),
                "Chicago_(band)" to page("Chicago (band)", "American rock band", extract = "A band."),
            ),
        )

        assertEquals("A band.", source(api).bioFor("deezer:artist:8", "Chicago")?.text)
    }

    @Test
    fun `a word that merely contains a musical one is not a musician`() = runTest {
        // "abandoned" contains "band"; "adjacent" contains "dj". Both must miss.
        val api = FakeWikipedia(
            titles = listOf("Sanctuary"),
            pages = mapOf("Sanctuary" to page("Sanctuary", "Abandoned village adjacent to the river")),
        )

        assertNull(source(api).bioFor("deezer:artist:9", "Sanctuary"))
    }

    @Test
    fun `the qualifier counts as a description, so a band page still passes`() = runTest {
        val api = FakeWikipedia(
            titles = listOf("Ghost (Swedish band)"),
            pages = mapOf("Ghost_(Swedish_band)" to page("Ghost (Swedish band)", description = null)),
        )

        assertEquals("A paragraph about them.", source(api).bioFor("deezer:artist:3", "Ghost")?.text)
    }

    @Test
    fun `a page about someone else entirely is not even fetched`() = runTest {
        val api = FakeWikipedia(titles = listOf("Some Other Artist"))

        assertNull(source(api).bioFor("deezer:artist:4", "Daft Punk"))
        assertEquals("no candidate matched the name, so no second request", 0, api.summaries)
    }

    @Test
    fun `the second visit costs nothing`() = runTest {
        val file = File(tmp.root, "bios.json")
        val api = FakeWikipedia(
            titles = listOf("Daft Punk"),
            pages = mapOf("Daft_Punk" to page("Daft Punk", "French electronic music duo")),
        )
        source(api, file).bioFor("deezer:artist:27", "Daft Punk")

        val again = source(api, file).bioFor("deezer:artist:27", "Daft Punk")

        assertEquals("A paragraph about them.", again?.text)
        assertEquals(1, api.searches)
    }

    @Test
    fun `an absence is remembered, and retried a month later`() = runTest {
        val file = File(tmp.root, "bios.json")
        val api = FakeWikipedia(titles = emptyList())
        val start = Instant.parse("2026-07-30T12:00:00Z")
        source(api, file, start).bioFor("deezer:artist:5", "Nobody")

        // Same week: not asked again.
        source(api, file, start.plusSeconds(3 * 86_400)).bioFor("deezer:artist:5", "Nobody")
        assertEquals(1, api.searches)

        // Two months on: worth another look.
        source(api, file, start.plusSeconds(60 * 86_400)).bioFor("deezer:artist:5", "Nobody")
        assertEquals(2, api.searches)
    }

    @Test
    fun `a network failure is simply no biography`() = runTest {
        assertNull(source(FakeWikipedia(failSearch = true)).bioFor("deezer:artist:6", "Daft Punk"))
    }

    @Test
    fun `a blank name asks nothing`() = runTest {
        val api = FakeWikipedia()

        assertNull(source(api).bioFor("deezer:artist:7", "  "))
        assertEquals(0, api.searches)
    }
}
