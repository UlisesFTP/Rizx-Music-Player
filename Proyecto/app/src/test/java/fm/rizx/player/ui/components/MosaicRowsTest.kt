package fm.rizx.player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicRowsTest {

    private fun tiles(n: Int) = List(n) { MosaicTile(key = "t$it", label = "MIX", title = "Tile $it") }

    @Test
    fun `every tile lands in exactly one row, in order`() {
        repeat(30) { seed ->
            val laid = mosaicRows(tiles(6), seed).flatMap { it.tiles }.map { it.key }
            assertEquals("seed $seed", List(6) { "t$it" }, laid)
        }
    }

    @Test
    fun `a full-width row holds one tile and a pair row holds two`() {
        repeat(30) { seed ->
            mosaicRows(tiles(7), seed).forEach { row ->
                assertEquals("seed $seed", if (row.wide) 1 else 2, row.tiles.size)
            }
        }
    }

    @Test
    fun `no lone half-width tile is ever left at the end`() {
        // A single tile in a two-column row reads as a tile that failed to load.
        for (count in 1..9) {
            repeat(20) { seed ->
                val rows = mosaicRows(tiles(count), seed)
                assertTrue("count $count seed $seed", rows.none { !it.wide && it.tiles.size == 1 })
            }
        }
    }

    @Test
    fun `no two full-width rows back to back — they eat the screen`() {
        repeat(30) { seed ->
            val rows = mosaicRows(tiles(6), seed)
            // Except at the very end, where a leftover single tile has nowhere else to go.
            rows.dropLast(1).zipWithNext().forEach { (a, b) ->
                assertTrue("seed $seed", !(a.wide && b.wide))
            }
        }
    }

    @Test
    fun `pair rows never run long enough to look like a plain grid`() {
        repeat(30) { seed ->
            var run = 0
            mosaicRows(tiles(9), seed).forEach { row ->
                run = if (row.wide) 0 else run + 1
                assertTrue("seed $seed ran $run pair rows", run <= 3)
            }
        }
    }

    @Test
    fun `the arrangement varies with the seed but never within one`() {
        val layouts = (0 until 25).map { seed -> mosaicRows(tiles(6), seed).map { it.wide } }

        assertTrue("only one arrangement over 25 seeds", layouts.distinct().size > 1)
        assertEquals(mosaicRows(tiles(6), 9).map { it.wide }, mosaicRows(tiles(6), 9).map { it.wide })
    }

    @Test
    fun `two tiles read side by side, one takes the full width`() {
        assertEquals(listOf(false), mosaicRows(tiles(2), 5).map { it.wide })
        assertEquals(listOf(true), mosaicRows(tiles(1), 5).map { it.wide })
        assertTrue(mosaicRows(emptyList(), 5).isEmpty())
    }
}
