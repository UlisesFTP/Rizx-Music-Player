package fm.rizx.player.playback.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioCacheKeyTest {

    @Test
    fun `the same song in two codecs never shares a cache resource`() {
        val aac = audioCacheKey("deezer:123", "m4a")
        val opus = audioCacheKey("deezer:123", "opus")

        assertNotEquals(aac, opus)
        // Both still name the same song, so a prefix scan finds either copy.
        listOf(aac, opus).forEach { assertEquals(true, it.startsWith("deezer:123#")) }
    }

    @Test
    fun `the codec is normalized so casing or padding can't split one format into two buckets`() {
        assertEquals(audioCacheKey("deezer:1", "m4a"), audioCacheKey("deezer:1", " M4A "))
    }

    @Test
    fun `a stream with no reported codec still gets a stable bucket`() {
        assertEquals(audioCacheKey("deezer:1", null), audioCacheKey("deezer:1", ""))
        assertEquals("deezer:1#raw", audioCacheKey("deezer:1", null))
    }
}
