package fm.rizx.player.data.local.store

import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiscoveryFeedbackStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun file() = File(temp.root, "discovery_feedback.json")
    private fun store() = DiscoveryFeedbackStore(file(), io = Dispatchers.Unconfined)

    @Test
    fun `hidden artists are kept by name key and survive a restart`() = runTest {
        store().apply {
            hideArtist("Bad Bunny")
            hidePlaylist("deezer:playlist:1")
        }

        val reopened = store().hidden.first()

        assertEquals(setOf(ArtistNameMatching.key("BAD BUNNY")), reopened.artistKeys)
        assertEquals(setOf("deezer:playlist:1"), reopened.playlistKeys)
    }

    @Test
    fun `unhide and clear bring things back`() = runTest {
        val s = store()
        s.hideArtist("Feid")
        s.hideArtist("Karol G")
        s.hidePlaylist("p")

        s.unhideArtist("feid")
        assertEquals(setOf(ArtistNameMatching.key("Karol G")), s.hidden.first().artistKeys)

        s.clear()
        assertTrue(s.hidden.first().isEmpty)
    }

    @Test
    fun `seeds are cleaned, capped, and picking them also retires the picker`() = runTest {
        val s = store()
        assertFalse(s.dismissed.first())

        s.setSeeds(listOf(" Rosalía ", "rosalia", "", "Feid") + List(20) { "Artist $it" })

        val seeds = s.seeds.first()
        assertEquals("Rosalía", seeds.first())
        assertEquals("one entry per artist", 1, seeds.count { ArtistNameMatching.key(it) == ArtistNameMatching.key("Rosalía") })
        assertEquals(10, seeds.size)
        assertTrue(s.dismissed.first())
    }

    @Test
    fun `a corrupt file means nothing hidden, never a crash`() = runTest {
        file().writeText("{definitely not json")

        assertTrue(store().hidden.first().isEmpty)
        assertEquals(emptyList<String>(), store().seeds.first())
    }
}
