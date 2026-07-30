package fm.rizx.player.ui.home

import kotlin.random.Random

/** One block of the Home overview, in the order it is to be emitted. */
sealed interface WovenBlock {
    /** The n-th row of the mosaic wall. */
    data class Tiles(val row: Int) : WovenBlock

    /** The strip identified by [key] — a carousel, a skeleton row standing in for one, the poster. */
    data class Strip(val key: String) : WovenBlock
}

/**
 * Weaves the Home overview: **shapes alternate and their order varies**, instead of a wall of tiles
 * followed by a tail of identical carousels in a fixed order.
 *
 * Two properties matter more than the variety itself:
 *
 * 1. **Stable for a given [seed].** The Home is a `LazyColumn` with keyed items; an order drawn per frame
 *    would reshuffle sections under the reader's thumb. The caller seeds this from something that changes
 *    slowly — what the mixes are about, and the day — so the page is fixed while you read it and laid out
 *    differently tomorrow.
 * 2. **Ordered per key, not as a permutation of positions.** Strips are sorted by a hash of their own key,
 *    so a strip that turns out empty and drops out leaves every other strip in the same relative place. A
 *    permutation would have re-ordered the whole page the moment a personalized row came back empty — and
 *    that row collapsing is a documented, ordinary event.
 *
 * Tiles open the weave, because the pick band above them is already a tile and the strongest content
 * belongs at the top rather than in a lottery. From there it takes one or two of each shape at a time.
 */
fun weaveHome(tileRows: Int, stripKeys: List<String>, seed: Int): List<WovenBlock> {
    val rng = Random(seed)
    val strips = stripKeys.sortedBy { spread(seed, it) }
    val out = mutableListOf<WovenBlock>()
    var tile = 0
    var strip = 0
    while (tile < tileRows || strip < strips.size) {
        repeat(group(rng, tileRows - tile)) { out += WovenBlock.Tiles(tile++) }
        repeat(group(rng, strips.size - strip)) { out += WovenBlock.Strip(strips[strip++]) }
    }
    return out
}

/** One or two at a time — the alternation that makes the page read as woven rather than stacked. */
private fun group(rng: Random, remaining: Int): Int =
    if (remaining <= 0) 0 else rng.nextInt(1, MAX_GROUP + 1).coerceAtMost(remaining)

/**
 * A stable spread for one key: the same key always sorts to the same place for a given seed, and two
 * similar keys land far apart (Knuth's multiplicative hash, then an xor-shift to avalanche the low bits —
 * without it, `"chart-1"` and `"chart-2"` would stay neighbours).
 */
private fun spread(seed: Int, key: String): Int {
    var h = seed xor key.hashCode()
    h *= -0x61c88647
    return h xor (h ushr 16)
}

private const val MAX_GROUP = 2
