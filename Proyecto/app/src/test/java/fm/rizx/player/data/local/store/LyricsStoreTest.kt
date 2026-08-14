package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.Lyrics
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * What the cache does when the code that filled it changes its mind.
 *
 * A cached lyric is the *answer* a matcher gave, so fixing the matcher doesn't fix what it already
 * answered — every song played before the fix would keep its wrong-language words forever.
 */
class LyricsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(file: File) = LyricsStore(file) { Instant.parse("2026-08-13T00:00:00Z") }

    private fun lyrics(text: String) = Lyrics(lines = listOf(LyricLine(0, text)), sourceName = "NetEase")

    @Test
    fun `what this version wrote is read back`() = runTest {
        val file = temp.newFile("lyrics.json")
        store(file).put("deezer:1", lyrics("첫눈에 널"))

        assertEquals("첫눈에 널", store(file).get("deezer:1")?.lyrics?.lines?.first()?.text)
    }

    @Test
    fun `an entry from before the matcher was fixed is dropped`() = runTest {
        val file = temp.newFile("lyrics.json")
        file.writeText(olderEntry(key = "deezer:1", pinned = false))

        assertNull(store(file).get("deezer:1"))
    }

    @Test
    fun `but a pinned entry survives, because it is the user's own pick`() = runTest {
        // Nothing to re-run: the user chose these words by hand, so there is no match to correct.
        val file = temp.newFile("lyrics.json")
        file.writeText(olderEntry(key = "deezer:2", pinned = true))

        assertNotNull(store(file).get("deezer:2"))
    }

    @Test
    fun `re-caching stamps the current version`() = runTest {
        val file = temp.newFile("lyrics.json")
        file.writeText(olderEntry(key = "deezer:1", pinned = false))
        val store = store(file)

        store.put("deezer:1", lyrics("첫눈에 널"))

        assertEquals(LyricsStore.SCHEMA, store(file).get("deezer:1")?.schema)
    }

    @Test
    fun `swapping the words keeps the offset and the pin`() = runTest {
        // The reading switch rewrites the words of an entry the user may have already corrected.
        val file = temp.newFile("lyrics.json")
        val store = store(file)
        store.put("deezer:1", lyrics("沈むように"), pinned = true)
        store.setOffset("deezer:1", 750L)

        store.replaceLyrics("deezer:1", lyrics("shi zu mu yo u ni"))

        val stored = store.get("deezer:1")
        assertEquals("shi zu mu yo u ni", stored?.lyrics?.lines?.first()?.text)
        assertEquals(750L, stored?.offsetMs)
        assertEquals(true, stored?.pinned)
    }

    @Test
    fun `a file this version cannot read leaves nothing cached, and does not throw`() = runTest {
        val file = temp.newFile("lyrics.json")
        file.writeText("{ not json at all")

        assertNull(store(file).get("deezer:1"))
    }

    /** An entry as an older build wrote it: no `schema` field at all. */
    private fun olderEntry(key: String, pinned: Boolean) = """
        {"entries":[{"key":"$key","value":{
            "lyrics":{"lines":[{"timeMs":0,"text":"夜に駆ける"}],"sourceName":"NetEase"},
            "pinned":$pinned,
            "fetchedAtIso":"2026-08-01T00:00:00Z"
        }}]}
    """.trimIndent()
}
