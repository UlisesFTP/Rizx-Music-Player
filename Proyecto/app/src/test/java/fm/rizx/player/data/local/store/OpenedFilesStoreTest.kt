package fm.rizx.player.data.local.store

import fm.rizx.player.data.local.media.fileTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The recents list's contract: newest first, re-opening moves up instead of duplicating, the cap prunes
 * from the tail *and reports what it pruned* (whose grants the repository must release), and a corrupt
 * file degrades to empty.
 */
class OpenedFilesStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = OpenedFilesStore(File(temp.root, "opened.json"))

    private fun entry(n: Int) = OpenedFilesStore.Entry(
        track = fileTrack("content://doc/$n", "song $n.mp3", "Song $n", null, null, 60_000L),
        mimeType = "audio/mpeg",
        openedAtIso = "2026-08-02T00:00:${"%02d".format(n % 60)}Z",
    )

    @Test
    fun `round-trips newest first and survives a reopen`() = runTest {
        val s = store()
        s.upsert(listOf(entry(1)))
        s.upsert(listOf(entry(2)))

        val reloaded = store().load()

        assertEquals(listOf("Song 2", "Song 1"), reloaded.map { it.track.title })
        assertEquals("audio/mpeg", reloaded.first().mimeType)
    }

    @Test
    fun `re-opening a file moves it up rather than duplicating it`() = runTest {
        val s = store()
        s.upsert(listOf(entry(1), entry(2)))
        s.upsert(listOf(entry(1)))

        assertEquals(listOf("Song 1", "Song 2"), s.load().map { it.track.title })
    }

    @Test
    fun `the cap prunes the oldest and returns them for grant release`() = runTest {
        val s = store()
        s.upsert((1..OpenedFilesStore.MAX_ENTRIES).map { entry(it) }.reversed()) // 50..1, newest=50

        val pruned = s.upsert(listOf(entry(99)))

        assertEquals(1, pruned.size)
        assertEquals("Song 1", pruned.single().track.title) // the oldest fell off
        assertEquals(OpenedFilesStore.MAX_ENTRIES, s.load().size)
        assertEquals("Song 99", s.load().first().track.title)
    }

    @Test
    fun `remove drops exactly one entry`() = runTest {
        val s = store()
        s.upsert(listOf(entry(1), entry(2)))

        s.remove("file:content://doc/1")

        assertEquals(listOf("Song 2"), s.load().map { it.track.title })
    }

    @Test
    fun `a corrupt file degrades to empty, never throws`() {
        File(temp.root, "opened.json").writeText("{ definitely not json ]")

        assertTrue(store().load().isEmpty())
    }
}
