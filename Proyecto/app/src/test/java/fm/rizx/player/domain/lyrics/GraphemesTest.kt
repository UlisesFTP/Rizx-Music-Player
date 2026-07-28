package fm.rizx.player.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep advances letter by letter, and a *letter* is not a `Char`. Every case here is one the naive
 * `toCharArray()` version would have drawn half of.
 */
class GraphemesTest {

    private fun count(text: String) = Graphemes.count(Graphemes.boundaries(text))

    @Test
    fun `empty text has a single boundary and no graphemes`() {
        assertEquals(listOf(0), Graphemes.boundaries("").toList())
        assertEquals(0, count(""))
    }

    @Test
    fun `plain ascii is one grapheme per char`() {
        assertEquals(4, count("hola"))
        assertEquals(listOf(0, 1, 2, 3, 4), Graphemes.boundaries("hola").toList())
    }

    @Test
    fun `a combining accent stays attached to its letter`() {
        // "café" written the hard way: e + U+0301. Four letters, five chars.
        val text = "café"

        assertEquals(5, text.length)
        assertEquals(4, count(text))
    }

    @Test
    fun `a ZWJ emoji family is one grapheme, not eight`() {
        val family = "👨‍👩‍👧"

        assertTrue(family.length > 2)
        assertEquals(1, count(family))
    }

    @Test
    fun `a surrogate pair is never split`() {
        val text = "a🎵b" // a 🎵 b
        val bounds = Graphemes.boundaries(text)

        assertEquals(3, Graphemes.count(bounds))
        // The musical note occupies chars 1..2 as a unit.
        assertEquals(listOf(0, 1, 3, 4), bounds.toList())
    }

    @Test
    fun `devanagari does not fall apart into one cluster per code point`() {
        // क्षि is four code points that a reader sees as one or two letters. The exact answer depends on
        // the segmenter: the JVM's java.text.BreakIterator says two (it stops at the virama), Android's
        // ICU-backed one says one. Both are far from four, which is what matters — the assertion is
        // deliberately loose so this test means the same thing on either.
        val text = "क्षि"

        assertEquals(4, text.length)
        assertTrue("got ${count(text)} clusters", count(text) <= 2)
    }

    @Test
    fun `arabic text counts by letter`() {
        val text = "مرحبا" // مرحبا

        assertEquals(5, count(text))
    }

    @Test
    fun `index lookup snaps a char offset onto its cluster`() {
        val text = "a🎵b"
        val bounds = Graphemes.boundaries(text) // [0, 1, 3, 4]

        assertEquals(0, Graphemes.indexAt(bounds, 0))
        assertEquals(1, Graphemes.indexAt(bounds, 1))
        // Landing in the middle of the surrogate pair resolves to the cluster that contains it.
        assertEquals(1, Graphemes.indexAt(bounds, 2))
        assertEquals(2, Graphemes.indexAt(bounds, 3))
        assertEquals(3, Graphemes.indexAt(bounds, 4))
    }
}
