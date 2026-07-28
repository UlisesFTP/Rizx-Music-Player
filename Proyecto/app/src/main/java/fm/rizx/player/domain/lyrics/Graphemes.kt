package fm.rizx.player.domain.lyrics

import java.text.BreakIterator

/**
 * Grapheme-cluster arithmetic for the karaoke sweep.
 *
 * The sweep advances "letter by letter", and a *letter* is not a `Char`. `👨‍👩‍👧` is 8 chars, `é` written as
 * `e` + U+0301 is 2, and Devanagari `क्षि` is 4 — stepping through `toCharArray()` would draw half an
 * emoji and split combining marks off the letters they belong to. [BreakIterator] is the JDK's
 * implementation of the Unicode text-segmentation rules and is available on Android as-is, so the domain
 * layer can use it without reaching for an Android API.
 *
 * Boundaries are expressed as char offsets so they can be handed straight to a `TextLayoutResult`.
 */
object Graphemes {

    /**
     * Char offsets of every grapheme boundary in [text], always starting at `0` and ending at
     * `text.length`. A string of *n* graphemes yields *n + 1* boundaries; empty text yields `[0]`.
     */
    fun boundaries(text: String, iterator: BreakIterator = BreakIterator.getCharacterInstance()): IntArray {
        if (text.isEmpty()) return intArrayOf(0)
        iterator.setText(text)
        // Sized for the worst case (every char its own grapheme) and trimmed once at the end — cheaper
        // than growing a list per line of a 120-line lyric.
        val out = IntArray(text.length + 1)
        var n = 0
        var at = iterator.first()
        while (at != BreakIterator.DONE) {
            out[n++] = at
            at = iterator.next()
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /** How many graphemes [boundaries] describes. */
    fun count(boundaries: IntArray): Int = (boundaries.size - 1).coerceAtLeast(0)

    /**
     * The index of the grapheme containing [charOffset] — i.e. the largest `i` with
     * `boundaries[i] <= charOffset`. Used to snap a word's char span onto cluster edges, so a word that
     * begins mid-cluster can never cut one in half.
     */
    fun indexAt(boundaries: IntArray, charOffset: Int): Int {
        var lo = 0
        var hi = boundaries.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (boundaries[mid] <= charOffset) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
