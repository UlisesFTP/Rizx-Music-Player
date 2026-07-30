package fm.rizx.player.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWeaveTest {

    private fun strips(n: Int) = List(n) { "strip-$it" }

    @Test
    fun `every block is emitted exactly once`() {
        val woven = weaveHome(tileRows = 4, stripKeys = strips(6), seed = 7)

        assertEquals((0 until 4).toList(), woven.filterIsInstance<WovenBlock.Tiles>().map { it.row }.sorted())
        assertEquals(strips(6).sorted(), woven.filterIsInstance<WovenBlock.Strip>().map { it.key }.sorted())
        assertEquals(10, woven.size)
    }

    @Test
    fun `tile rows stay in order — the wall reads top to bottom`() {
        // Their *positions* vary; their sequence must not, or the wall's rows would be shuffled among
        // themselves and the entrance stagger would cascade backwards.
        repeat(20) { seed ->
            val order = weaveHome(6, strips(5), seed).filterIsInstance<WovenBlock.Tiles>().map { it.row }
            assertEquals(order.sorted(), order)
        }
    }

    @Test
    fun `the same seed always weaves the same page`() {
        assertEquals(weaveHome(4, strips(6), 42), weaveHome(4, strips(6), 42))
    }

    @Test
    fun `different seeds weave different pages`() {
        // Otherwise "organic" is just a fixed template with extra steps. Across a spread of seeds at
        // least a couple of distinct arrangements must appear.
        val shapes = (0 until 25).map { seed ->
            weaveHome(4, strips(6), seed).map { it is WovenBlock.Tiles }
        }
        assertTrue("only one arrangement over 25 seeds", shapes.distinct().size > 1)
        assertNotEquals(weaveHome(4, strips(6), 1), weaveHome(4, strips(6), 2))
    }

    @Test
    fun `shapes alternate instead of stacking`() {
        // The whole point: never the entire wall and then every carousel. Neither shape may run for more
        // than two blocks while the other still has something to contribute.
        repeat(30) { seed ->
            val woven = weaveHome(5, strips(7), seed)
            var run = 1
            for (i in 1 until woven.size) {
                val same = (woven[i] is WovenBlock.Tiles) == (woven[i - 1] is WovenBlock.Tiles)
                run = if (same) run + 1 else 1
                val tilesLeft = woven.drop(i + 1).any { it is WovenBlock.Tiles }
                val stripsLeft = woven.drop(i + 1).any { it is WovenBlock.Strip }
                if (tilesLeft && stripsLeft) assertTrue("seed $seed ran $run of one shape", run <= 2)
            }
        }
    }

    @Test
    fun `tiles open the page — the pick band above them is a tile too`() {
        repeat(20) { seed ->
            assertTrue(weaveHome(3, strips(5), seed).first() is WovenBlock.Tiles)
        }
    }

    @Test
    fun `a strip that drops out leaves the others in the same relative order`() {
        // A personalized row whose source comes back empty disappears; that is ordinary. Ordering strips
        // by a hash of their own key — rather than shuffling positions — is what keeps that from
        // re-ordering the whole page under the reader.
        val all = strips(7)
        val before = weaveHome(3, all, 11).filterIsInstance<WovenBlock.Strip>().map { it.key }
        val after = weaveHome(3, all - "strip-3", 11).filterIsInstance<WovenBlock.Strip>().map { it.key }

        assertEquals(before.filterNot { it == "strip-3" }, after)
    }

    @Test
    fun `it degenerates safely`() {
        assertTrue(weaveHome(0, emptyList(), 3).isEmpty())
        assertEquals(listOf(WovenBlock.Tiles(0)), weaveHome(1, emptyList(), 3))
        assertEquals(1, weaveHome(0, strips(1), 3).size)
    }
}
