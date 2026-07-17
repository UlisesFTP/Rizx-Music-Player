package fm.rizx.player.data.remote.audius

import fm.rizx.player.domain.model.StreamProtocol
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiusMapperTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(body: String): List<AudiusTrackDto> =
        json.decodeFromString(AudiusTracksResponse.serializer(), body).data

    private val sample = """
        {"data":[
          {"id":"VPEA2ka","title":"GIRLS","user":{"name":"Luci","handle":"luci.official"},
           "duration":105,"artwork":{"150x150":"https://art/150.jpg","480x480":"https://art/480.jpg",
           "1000x1000":"https://art/1000.jpg"},"is_streamable":true,"unknownField":"ignored"},
          {"id":"ZZZ","title":"No Artwork","duration":200}
        ]}
    """.trimIndent()

    @Test
    fun `maps a track to a candidate with audius identity and ms duration`() {
        val candidate = decode(sample).first().toStreamCandidateOrNull()!!

        assertEquals("VPEA2ka", candidate.id)
        assertEquals(AudiusIds.STREAMING, candidate.source.provider)
        assertEquals(105_000L, candidate.durationMs) // seconds → ms
        assertEquals("https://art/1000.jpg", candidate.thumbnail) // prefers largest
        assertNull(candidate.stream) // no URL until phase 2
    }

    @Test
    fun `rows without id or title are dropped`() {
        val body = """{"data":[{"title":"No Id"},{"id":"x"}]}"""
        assertTrue(decode(body).mapNotNull { it.toStreamCandidateOrNull() }.isEmpty())
    }

    @Test
    fun `audiusStream builds the full-length stream url for the chosen host`() {
        val stream = audiusStream("https://dn1.audius.co", "VPEA2ka", 105_000L)

        assertEquals("https://dn1.audius.co/v1/tracks/VPEA2ka/stream?app_name=${AudiusIds.APP_NAME}", stream.url)
        assertEquals(StreamProtocol.HTTPS, stream.protocol)
        assertEquals("mp3", stream.codec)
        assertEquals(105_000L, stream.durationMs)
        assertEquals(AudiusIds.STREAMING, stream.source.provider)
    }
}
