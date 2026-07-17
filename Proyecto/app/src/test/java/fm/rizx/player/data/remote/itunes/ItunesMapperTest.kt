package fm.rizx.player.data.remote.itunes

import fm.rizx.player.domain.model.StreamProtocol
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure DTO → domain mapping tests. No network; JSON is decoded exactly as the API returns it. */
class ItunesMapperTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private fun decode(body: String): List<ItunesResultDto> =
        json.decodeFromString(ItunesSearchResponse.serializer(), body).results

    private val sample = """
        {"resultCount":2,"results":[
          {"wrapperType":"track","kind":"song","trackId":111,"artistId":9,"collectionId":77,
           "trackName":"Velvet Hours","artistName":"Aurora Lane","collectionName":"Nightfall",
           "previewUrl":"https://audio.example.com/velvet.m4a","artworkUrl100":"https://art/100x100bb.jpg",
           "trackTimeMillis":180000,"trackNumber":3,"discNumber":1,"primaryGenreName":"Indie",
           "trackViewUrl":"https://music.example.com/velvet","unknownField":"ignored"},
          {"wrapperType":"track","kind":"song","trackId":112,"artistId":9,"collectionId":77,
           "trackName":"Goldenrod","artistName":"Aurora Lane","collectionName":"Nightfall",
           "previewUrl":"https://audio.example.com/gold.m4a","artworkUrl100":"https://art2/100x100bb.jpg",
           "trackTimeMillis":200000}
        ]}
    """.trimIndent()

    @Test
    fun `maps a song row to a full track with itunes identity`() {
        val track = decode(sample).first().toTrackOrNull()!!

        assertEquals("Velvet Hours", track.title)
        assertEquals("itunes", track.source.provider)
        assertEquals("111", track.source.id)
        assertEquals("https://music.example.com/velvet", track.source.url)
        assertEquals(180000L, track.durationMs)
        assertEquals("Aurora Lane", track.artists.single().name)
        assertEquals("Nightfall", track.album?.title)
        assertEquals(listOf("Indie"), track.tags)
        assertEquals("1", track.disc)
    }

    @Test
    fun `artwork upsizes the 100px thumbnail to a 600px cover`() {
        val art = decode(sample).first().toTrackOrNull()!!.artwork!!.items

        val cover = art.first { it.width == 600 }
        assertEquals("https://art/600x600bb.jpg", cover.url)
        assertTrue(art.any { it.url == "https://art/100x100bb.jpg" })
    }

    @Test
    fun `search results derive distinct artists and albums from song rows`() {
        val results = decode(sample).toSearchResults()

        assertEquals(2, results.tracks.size)
        assertEquals(1, results.artists.size)  // both songs share one artist
        assertEquals(1, results.albums.size)   // and one album
        assertEquals("Aurora Lane", results.artists.single().name)
        assertEquals("itunes", results.albums.single().source.provider)
        assertEquals("album:77", results.albums.single().source.id)
    }

    @Test
    fun `rows without a trackId or trackName are dropped`() {
        val body = """{"resultCount":2,"results":[
            {"wrapperType":"track","artistName":"No Id"},
            {"trackId":5,"artistName":"No Name"}
        ]}"""
        assertTrue(decode(body).toSearchResults().tracks.isEmpty())
    }

    @Test
    fun `stream candidate carries streaming identity but no url`() {
        val candidate = decode(sample).first().toStreamCandidateOrNull()!!

        assertEquals("111", candidate.id)
        assertEquals(ItunesIds.STREAMING, candidate.source.provider)
        assertNull(candidate.stream)
        assertEquals(180000L, candidate.durationMs)
    }

    @Test
    fun `toStream produces an https preview stream from previewUrl`() {
        val stream = decode(sample).first().toStream("111")!!

        assertEquals("https://audio.example.com/velvet.m4a", stream.url)
        assertEquals(StreamProtocol.HTTPS, stream.protocol)
        assertEquals(ItunesIds.STREAMING, stream.source.provider)
        assertEquals("aac", stream.codec)
    }

    @Test
    fun `toStream is null when there is no preview`() {
        val body = """{"results":[{"trackId":5,"trackName":"No Preview"}]}"""
        assertNull(decode(body).first().toStream("5"))
        assertNotNull(decode(body).first().toStreamCandidateOrNull()) // still a valid candidate
    }
}
