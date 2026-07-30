package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.SoundGenre
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoEqStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(file: File = File(tmp.root, "auto_eq.json")) = AutoEqStore(file)

    private fun entry(
        genre: SoundGenre = SoundGenre.REGGAETON,
        curve: List<Float> = listOf(4.5f, -5.1f, -3.3f, 0.9f, 2.7f),
        adapted: Boolean = true,
    ) = StoredAutoEq(genre = genre, label = "Reggaetón", curveDb = curve, adapted = adapted, bandCount = curve.size)

    @Test
    fun `a curve survives the round trip, stamped with when it was worked out`() = runTest {
        val file = File(tmp.root, "auto_eq.json")
        store(file).put("deezer:123", entry())

        val read = store(file).get("deezer:123", bandCount = 5)!!

        assertEquals(SoundGenre.REGGAETON, read.genre)
        assertEquals("Reggaetón", read.label)
        assertEquals(listOf(4.5f, -5.1f, -3.3f, 0.9f, 2.7f), read.curveDb)
        assertTrue(read.adapted)
        assertTrue("the store owns the clock", read.computedAtIso.isNotEmpty())
    }

    @Test
    fun `a curve written for another device's bands is not handed back`() = runTest {
        // The values *are* this device's bands. Replaying a five-band curve on a ten-band equalizer would
        // apply the wrong frequencies silently, which is worse than recomputing.
        val file = File(tmp.root, "auto_eq.json")
        store(file).put("deezer:123", entry())

        assertNull(store(file).get("deezer:123", bandCount = 10))
        assertNull(store(file).get("deezer:456", bandCount = 5))
    }

    @Test
    fun `re-computing a song replaces its entry rather than adding one`() = runTest {
        val file = File(tmp.root, "auto_eq.json")
        val s = store(file)
        s.put("deezer:123", entry(adapted = false))
        s.put("deezer:123", entry(adapted = true))

        assertTrue(store(file).get("deezer:123", bandCount = 5)!!.adapted)
    }

    @Test
    fun `a corrupt file reads as nothing cached`() = runTest {
        val file = File(tmp.root, "auto_eq.json").apply { writeText("{ not json") }

        assertNull(store(file).get("deezer:123", bandCount = 5))
        // And it recovers: a write over the wreckage is readable again.
        store(file).put("deezer:123", entry())
        assertEquals(SoundGenre.REGGAETON, store(file).get("deezer:123", bandCount = 5)?.genre)
    }

    @Test
    fun `clear empties the cache and deletes the file`() = runTest {
        val file = File(tmp.root, "auto_eq.json")
        val s = store(file)
        s.put("deezer:123", entry())
        s.clear()

        assertNull(store(file).get("deezer:123", bandCount = 5))
        assertTrue(!file.exists())
    }
}
