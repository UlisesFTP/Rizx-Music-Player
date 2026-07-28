package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four word-timed formats all have to land on the same model, or the karaoke view can't be shared. */
class WordSyncParsersTest {

    // ---- NetEase yrc ----

    @Test
    fun `yrc reads absolute word times and drops the JSON credit block`() {
        val raw = """
            {"t":0,"c":[{"tx":"作词: "},{"tx":"Future"}]}
            [3930,990](3930,270,0)Call (4200,60,0)it (4260,660,0)what
        """.trimIndent()

        val lines = YrcParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals(3930L, lines[0].timeMs)
        assertEquals("Call it what", lines[0].text)
        assertEquals(listOf("Call ", "it ", "what"), lines[0].words.map { it.text })
        // yrc word times are absolute, not relative to the line.
        assertEquals(listOf(3930L, 4200L, 4260L), lines[0].words.map { it.startMs })
        assertEquals(4200L, lines[0].words[0].endMs) // 3930 + 270
    }

    @Test
    fun `yrc survives junk without throwing`() {
        assertTrue(YrcParser.parse(null).isEmpty())
        assertTrue(YrcParser.parse("not a lyric at all").isEmpty())
    }

    // ---- KuGou krc ----

    @Test
    fun `krc lifts line-relative word offsets to absolute times`() {
        val raw = """
            [id:1]
            [1000,900]<0,300,0>Hola <300,600,0>mundo
        """.trimIndent()

        val lines = KrcParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("Hola mundo", lines[0].text)
        // The trap: 0 and 300 are offsets *from the line*, so they must become 1000 and 1300.
        assertEquals(listOf(1000L, 1300L), lines[0].words.map { it.startMs })
        assertEquals(listOf(1300L, 1900L), lines[0].words.map { it.endMs })
    }

    @Test
    fun `krc decoding of a non-krc payload is a miss, not a crash`() {
        assertEquals(null, KrcParser.decode("bm90IGtyYw==")) // "not krc", no magic header
        assertEquals(null, KrcParser.decode("!!!not base64!!!"))
        assertTrue(KrcParser.parseEncoded(null).isEmpty())
    }

    // ---- Musixmatch richsync ----

    @Test
    fun `richsync converts seconds to milliseconds and offsets from the line start`() {
        val body = """
            [{"ts":12.5,"te":14.0,"l":[{"c":"Hello ","o":0.0},{"c":"world","o":0.75}],"x":"Hello world"}]
        """.trimIndent()

        val lines = RichSyncParser.parse(body)

        assertEquals(1, lines.size)
        assertEquals(12_500L, lines[0].timeMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals(listOf(12_500L, 13_250L), lines[0].words.map { it.startMs })
        // The last word runs to the line's own end.
        assertEquals(14_000L, lines[0].words.last().endMs)
    }

    @Test
    fun `richsync degrades to empty on a malformed body`() {
        assertTrue(RichSyncParser.parse("{not json").isEmpty())
        assertTrue(RichSyncParser.parse(null).isEmpty())
    }

    // ---- Enhanced LRC (A2) ----

    @Test
    fun `enhanced LRC reads inline word stamps and never draws them`() {
        val lines = LrcParser.parse("[00:12.00]<00:12.00>Hey <00:12.50>you")

        assertEquals(1, lines.size)
        assertEquals("Hey you", lines[0].text)
        assertEquals(listOf(12_000L, 12_500L), lines[0].words.map { it.startMs })
    }

    @Test
    fun `a line with stray word stamps but no usable timing still renders clean text`() {
        // The bug this guards: the stamps used to survive into the visible text.
        val lines = LrcParser.parse("[00:05.00]plain line")

        assertEquals("plain line", lines[0].text)
        assertTrue(lines[0].words.isEmpty())
    }

    @Test
    fun `a repeated chorus keeps its text but not one stamp's word timings`() {
        val lines = LrcParser.parse("[00:10.00][01:20.00]<00:10.00>La <00:10.40>la")

        assertEquals(2, lines.size)
        assertEquals("La la", lines[0].text)
        // Word times are absolute, so reusing them for the second occurrence would light it up wrongly.
        assertTrue(lines.all { it.words.isEmpty() })
    }

    // ---- The model question the UI asks ----

    @Test
    fun `isWordSynced distinguishes karaoke from plain line timings`() {
        val words = Lyrics(lines = YrcParser.parse("[0,500](0,500,0)Hi"))
        val linesOnly = Lyrics(lines = LrcParser.parse("[00:00.00]Hi"))

        assertTrue(words.isWordSynced)
        assertTrue(linesOnly.isSynced)
        assertTrue(!linesOnly.isWordSynced)
    }
}
