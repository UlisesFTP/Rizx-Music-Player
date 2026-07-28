package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecsBlenderTest {

    private val blender = RecsBlender()

    private fun track(title: String, artist: String, provider: String, id: String) = Track(
        title = title,
        artists = listOf(ArtistCredit(name = artist)),
        source = ProviderRef(provider, id),
    )

    private fun <T> attributed(providerId: String, vararg items: T) =
        AttributedResult(providerId, providerId, items.toList())

    @Test
    fun `dedup keeps the Deezer copy regardless of section order`() {
        val apple = attributed(
            "applemusic-charts",
            track("Song A", "Artist", "applemusic", "9"),
            track("Song B", "Artist", "applemusic", "10"),
        )
        val deezer = attributed("deezer-dashboard", track("Song A", "Artist", "deezer", "1"))

        val out = blender.blendTracks(listOf(apple, deezer)) // Apple listed first on purpose

        assertEquals(2, out.size)
        assertEquals("deezer", out.first { it.title == "Song A" }.source.provider)
    }

    @Test
    fun `normalization merges feat tails and diacritics but keeps remixes separate`() {
        val deezer = attributed("deezer-dashboard", track("Corazón (feat. X)", "José", "deezer", "1"))
        val spotify = attributed(
            "spotify-charts",
            track("Corazon", "Jose", "spotify", "2"),
            track("Corazón (Remix)", "José", "spotify", "3"),
        )

        val out = blender.blendTracks(listOf(deezer, spotify))

        assertEquals(2, out.size)
        assertTrue(out.any { it.source.provider == "deezer" }) // the merged copy is Deezer's
        assertTrue(out.any { it.title.contains("Remix") }) // a remix is a different recording
    }

    @Test
    fun `weighted interleave leads with the heaviest source in its proportion`() {
        val deezer = attributed("deezer-dashboard", *(1..8).map { track("D$it", "a", "deezer", "d$it") }.toTypedArray())
        val spotify = attributed("spotify-charts", *(1..8).map { track("S$it", "a", "spotify", "s$it") }.toTypedArray())

        val out = blender.blendTracks(listOf(deezer, spotify))

        assertEquals(16, out.size)
        assertEquals("deezer", out.first().source.provider)
        // Weighted fair queuing at .40 vs .20 → 2:1 within the first six picks.
        val firstSix = out.take(6)
        assertEquals(4, firstSix.count { it.source.provider == "deezer" })
        assertEquals(2, firstSix.count { it.source.provider == "spotify" })
    }

    @Test
    fun `empty input blends to empty and unknown sources still flow`() {
        assertTrue(blender.blendTracks(emptyList()).isEmpty())

        val unknown = attributed("future-source", track("New", "Someone", "future", "1"))
        assertEquals(1, blender.blendTracks(listOf(unknown)).size)
    }

    @Test
    fun `artists dedup by normalized name keeping the preferred source`() {
        val deezer = attributed(
            "deezer-dashboard",
            ArtistRef(name = "Café Tacvba", source = ProviderRef("deezer", "artist:1")),
        )
        val apple = attributed(
            "applemusic-charts",
            ArtistRef(name = "Cafe Tacvba", source = ProviderRef("applemusic", "9")),
            ArtistRef(name = "Otro", source = ProviderRef("applemusic", "8")),
        )

        val out = blender.blendArtists(listOf(apple, deezer))

        assertEquals(2, out.size)
        assertEquals("deezer", out.first { it.name.startsWith("Caf") }.source.provider)
    }
}
