package fm.rizx.player.domain.discovery

import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryMergeTest {

    private fun pl(provider: String, id: String) = PlaylistRef(id = id, name = "$provider $id", source = ProviderRef(provider, "playlist:$id"))

    @Test
    fun `round-robin puts every platform in the row's head`() {
        val deezer = listOf(pl("deezer", "1"), pl("deezer", "2"), pl("deezer", "3"))
        val apple = listOf(pl("applemusic", "a"), pl("applemusic", "b"))
        val youtube = listOf(pl("youtube", "x"))

        val merged = DiscoveryMerge.interleave(listOf(deezer, apple, youtube), limit = 10)

        assertEquals(
            listOf("deezer 1", "applemusic a", "youtube x", "deezer 2", "applemusic b", "deezer 3"),
            merged.map { it.name },
        )
    }

    @Test
    fun `the same playlist from two answers is one card`() {
        val twice = pl("youtube", "x")
        val merged = DiscoveryMerge.interleave(listOf(listOf(twice), listOf(twice, pl("youtube", "y"))), limit = 10)

        assertEquals(listOf("youtube x", "youtube y"), merged.map { it.name })
    }

    @Test
    fun `stops at the limit and survives empty answers`() {
        val merged = DiscoveryMerge.interleave(listOf(emptyList(), List(20) { pl("deezer", "$it") }), limit = 12)

        assertEquals(12, merged.size)
        assertEquals(emptyList<PlaylistRef>(), DiscoveryMerge.interleave(emptyList(), limit = 12))
    }
}
