package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.domain.model.coverUrl
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicDtosTest {

    @Test
    fun `maps a song and upscales the RSS artwork`() {
        val track = AppleRssSong(
            id = "1814146675",
            name = "SUPERESTRELLA",
            artistName = "Aitana",
            artworkUrl100 = "https://is1-ssl.mzstatic.com/image/thumb/x/100x100bb.jpg",
            url = "https://music.apple.com/mx/album/x",
        ).toTrackOrNull()

        requireNotNull(track)
        assertEquals("SUPERESTRELLA", track.title)
        assertEquals("Aitana", track.artists.single().name)
        assertEquals(AppleMusicIds.PROVIDER, track.source.provider)
        assertEquals("1814146675", track.source.id)
        // The cover the player shows must be big enough to fill a phone, not the RSS thumb upscaled by
        // the display: Apple serves this size from the same path.
        assertTrue(track.artwork.coverUrl()!!.contains("1000x1000"))
        // …while the 100 px original stays available as a thumbnail, so a small tile isn't decoding 1000 px.
        assertTrue(track.artwork!!.items.any { it.url.contains("100x100") && it.width == 100 })
    }

    @Test
    fun `rows without id or name are dropped`() {
        assertNull(AppleRssSong(id = null, name = "x", artistName = "a").toTrackOrNull())
        assertNull(AppleRssSong(id = "1", name = null, artistName = "a").toTrackOrNull())
    }

    @Test
    fun `decodes the real feed envelope, ignoring unknown fields`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
        val body = """
            {"feed":{"title":"Top canciones","country":"mx","updated":"x","results":[
              {"artistName":"A","id":"1","name":"S1","artworkUrl100":"https://x/100x100bb.jpg","url":"u","genres":[{"genreId":"34","name":"Música"}]},
              {"artistName":"B","id":"2","name":"S2","releaseDate":"2025-05-29","kind":"songs"}
            ]}}
        """.trimIndent()

        val feed = json.decodeFromString(AppleRssResponse.serializer(), body).feed

        assertEquals("mx", feed.country)
        assertEquals(2, feed.results.size)
        assertEquals(2, feed.results.mapNotNull { it.toTrackOrNull() }.size)
    }
}
