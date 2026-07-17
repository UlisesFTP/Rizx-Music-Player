package fm.rizx.player.data.repository

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.QueueItemStatus
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRepositoryTest {

    // Deterministic ids ("q0", "q1", …) per test instance (JUnit makes a fresh instance per @Test).
    private var counter = 0
    private fun repo() = InMemoryQueueRepository(
        newId = { "q${counter++}" },
        nowIso = { "2026-01-01T00:00:00Z" },
    )

    private fun track(title: String) = Track(title = title, source = ProviderRef("fake", title))
    private fun tracks(vararg titles: String) = titles.map { track(it) }

    // ---- shuffle ----

    /** Enough items that a shuffle landing back on the original order is vanishingly unlikely (1/19!). */
    private fun manyTracks() = tracks(*Array(20) { "t$it" })

    @Test
    fun `shuffle keeps the song that is playing and reorders the rest`() {
        val repo = repo()
        repo.setQueue(manyTracks(), startIndex = 7, QueueContext())
        val playing = repo.state.value.current!!.id
        val before = repo.state.value.items.map { it.id }

        repo.setShuffle(true)

        val after = repo.state.value
        assertTrue(after.shuffleOn)
        assertEquals("the song playing must not change", playing, after.current!!.id)
        assertEquals("it leads the queue, so the rest plays after it", 0, after.currentIndex)
        assertEquals("no item may be lost or duplicated", before.toSet(), after.items.map { it.id }.toSet())
        assertNotEquals(before, after.items.map { it.id })
    }

    @Test
    fun `turning shuffle off restores the original order and stays on the same song`() {
        val repo = repo()
        repo.setQueue(manyTracks(), startIndex = 3, QueueContext())
        val before = repo.state.value.items.map { it.id }
        repo.setShuffle(true)
        val playing = repo.state.value.current!!.id

        repo.setShuffle(false)

        val after = repo.state.value
        assertFalse(after.shuffleOn)
        assertNull("the un-shuffle map is spent", after.unshuffledIds)
        assertEquals(before, after.items.map { it.id })
        assertEquals(playing, after.current!!.id)
    }

    @Test
    fun `tracks queued while shuffled survive un-shuffling, at the end`() {
        val repo = repo()
        repo.setQueue(tracks("a", "b", "c"), startIndex = 0, QueueContext())
        val original = repo.state.value.items.map { it.id }
        repo.setShuffle(true)
        repo.addToQueue(tracks("late"))

        repo.setShuffle(false)

        // The saved order has no place for "late", so it goes last rather than being dropped.
        val ids = repo.state.value.items.map { it.id }
        assertEquals(4, ids.size)
        assertEquals(original, ids.dropLast(1))
        assertEquals("late", repo.state.value.items.last().track.title)
    }

    @Test
    fun `shuffling an empty queue just remembers the choice for what plays next`() {
        val repo = repo()

        repo.setShuffle(true)

        assertTrue(repo.state.value.shuffleOn)
        assertTrue(repo.state.value.items.isEmpty())
    }

    @Test
    fun `with shuffle on, playing an album shuffles it from the tapped track`() {
        val repo = repo()
        repo.setShuffle(true)

        repo.setQueue(manyTracks(), startIndex = 5, QueueContext())

        val q = repo.state.value
        assertTrue(q.shuffleOn)
        assertEquals("the tapped track plays first", "t5", q.current!!.track.title)
        assertEquals(0, q.currentIndex)
        assertEquals(20, q.items.size)
        assertNotEquals((0 until 20).map { "t$it" }, q.items.map { it.track.title })
    }

    @Test
    fun `clearing the queue keeps repeat and shuffle as standing preferences`() {
        val repo = repo()
        repo.setQueue(tracks("a", "b"), startIndex = 0, QueueContext())
        repo.setShuffle(true)
        repo.setRepeatMode(RepeatMode.ALL)

        repo.clearQueue()

        assertTrue(repo.state.value.shuffleOn)
        assertEquals(RepeatMode.ALL, repo.state.value.repeatMode)
        assertNull("nothing left to un-shuffle", repo.state.value.unshuffledIds)
    }

    @Test
    fun `restoring a shuffled session brings its un-shuffle map back`() {
        val repo = repo()
        repo.setQueue(tracks("a", "b", "c"), startIndex = 0, QueueContext())
        repo.setShuffle(true)
        val saved = repo.state.value

        val reopened = repo()
        reopened.restore(saved.items, saved.currentIndex, saved.repeatMode, saved.context, saved.shuffleOn, saved.unshuffledIds)
        reopened.setShuffle(false)

        // Without the map the toggle would flip to off and leave the queue shuffled — a lie.
        assertEquals(saved.unshuffledIds, reopened.state.value.items.map { it.id })
    }

    @Test
    fun `add tracks appends and selects the first when empty`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B", "C"))

        val q = repo.state.value
        assertEquals(listOf("A", "B", "C"), q.items.map { it.track.title })
        assertEquals(0, q.currentIndex)
        assertEquals("A", q.current?.track?.title)
    }

    @Test
    fun `duplicate track gets unique queue item ids`() {
        val repo = repo()
        val song = track("Velvet Hours")
        repo.addToQueue(listOf(song, song))

        val q = repo.state.value
        assertEquals(2, q.items.size)
        assertNotEquals(q.items[0].id, q.items[1].id)
        // Same underlying track identity, different queue-item identity.
        assertEquals(q.items[0].track.source, q.items[1].track.source)
    }

    @Test
    fun `removing an item before the current one keeps the same item current`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B", "C"))
        repo.goToIndex(2) // current = C
        val currentId = repo.state.value.current!!.id

        repo.removeByIndices(listOf(0))

        val q = repo.state.value
        assertEquals(listOf("B", "C"), q.items.map { it.track.title })
        assertEquals(1, q.currentIndex)
        assertEquals(currentId, q.current?.id)
    }

    @Test
    fun `removing the current item slides the next item into the cursor`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B", "C"))
        repo.goToIndex(1) // current = B
        val removedId = repo.state.value.current!!.id

        repo.removeByIds(listOf(removedId))

        val q = repo.state.value
        assertEquals(listOf("A", "C"), q.items.map { it.track.title })
        assertEquals(1, q.currentIndex)
        assertEquals("C", q.current?.track?.title)
    }

    @Test
    fun `removing every item resets the cursor`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B"))
        repo.removeByIndices(listOf(0, 1))

        val q = repo.state.value
        assertTrue(q.items.isEmpty())
        assertEquals(-1, q.currentIndex)
        assertNull(q.current)
    }

    @Test
    fun `reordering the current item keeps it current`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B", "C")) // current = A (index 0)
        val currentId = repo.state.value.current!!.id

        repo.reorder(fromIndex = 0, toIndex = 2)

        val q = repo.state.value
        assertEquals(listOf("B", "C", "A"), q.items.map { it.track.title })
        assertEquals(2, q.currentIndex)
        assertEquals(currentId, q.current?.id)
    }

    @Test
    fun `reordering an item before the current one keeps the same item current`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B", "C"))
        repo.goToIndex(2) // current = C
        val currentId = repo.state.value.current!!.id

        repo.reorder(fromIndex = 0, toIndex = 1) // move A after B

        val q = repo.state.value
        assertEquals(listOf("B", "A", "C"), q.items.map { it.track.title })
        assertEquals(2, q.currentIndex)
        assertEquals(currentId, q.current?.id)
    }

    @Test
    fun `next and previous are bounded manual navigation`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B", "C")) // current 0

        assertTrue(repo.goToNext())
        assertEquals(1, repo.state.value.currentIndex)
        assertTrue(repo.goToNext())
        assertEquals(2, repo.state.value.currentIndex)
        assertFalse(repo.goToNext()) // at the end, no wrap
        assertEquals(2, repo.state.value.currentIndex)
        assertTrue(repo.goToPrevious())
        assertEquals(1, repo.state.value.currentIndex)
    }

    @Test
    fun `add next inserts after the current item`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B")) // current = A (index 0)
        val currentId = repo.state.value.current!!.id

        repo.addNext(tracks("X"))

        val q = repo.state.value
        assertEquals(listOf("A", "X", "B"), q.items.map { it.track.title })
        assertEquals(0, q.currentIndex)
        assertEquals(currentId, q.current?.id)
    }

    @Test
    fun `repeat off does not advance past the end`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B"))
        repo.goToIndex(1) // last
        repo.setRepeatMode(RepeatMode.OFF)

        assertFalse(repo.advanceOnTrackEnd())
        assertEquals(1, repo.state.value.currentIndex)
    }

    @Test
    fun `repeat one replays the same track`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B"))
        repo.goToIndex(0)
        repo.setRepeatMode(RepeatMode.ONE)

        assertTrue(repo.advanceOnTrackEnd())
        assertEquals(0, repo.state.value.currentIndex)
    }

    @Test
    fun `repeat all wraps to the start`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B"))
        repo.goToIndex(1) // last
        repo.setRepeatMode(RepeatMode.ALL)

        assertTrue(repo.advanceOnTrackEnd())
        assertEquals(0, repo.state.value.currentIndex)
    }

    @Test
    fun `clear preserves repeat mode`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B"))
        repo.setRepeatMode(RepeatMode.ALL)

        repo.clearQueue()

        val q = repo.state.value
        assertTrue(q.items.isEmpty())
        assertEquals(-1, q.currentIndex)
        assertEquals(RepeatMode.ALL, q.repeatMode)
    }

    @Test
    fun `updateItemState updates only the matching item`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B"))
        val id = repo.state.value.items[1].id

        repo.updateItemState(id, QueueItemStatus.LOADING, error = null)

        val items = repo.state.value.items
        assertEquals(QueueItemStatus.IDLE, items[0].status)
        assertEquals(QueueItemStatus.LOADING, items[1].status)
    }

    @Test
    fun `goToId jumps to the item and reports whether it moved`() {
        val repo = repo()
        repo.addToQueue(tracks("A", "B", "C"))
        val targetId = repo.state.value.items[2].id

        assertTrue(repo.goToId(targetId))
        assertEquals(2, repo.state.value.currentIndex)
        assertFalse(repo.goToId("does-not-exist"))
        assertEquals(2, repo.state.value.currentIndex)
    }

    private fun queueItem(id: String, title: String) =
        QueueItem(id = id, track = track(title), addedAtIso = "2026-01-01T00:00:00Z")

    @Test
    fun `restore replaces the queue with the given items, cursor and repeat mode`() {
        val repo = repo()
        repo.addToQueue(tracks("old")) // any pre-existing content is discarded by a restore

        val items = listOf(queueItem("r0", "A"), queueItem("r1", "B"), queueItem("r2", "C"))
        repo.restore(items, currentIndex = 2, repeatMode = RepeatMode.ALL)

        val q = repo.state.value
        assertEquals(listOf("A", "B", "C"), q.items.map { it.track.title })
        assertEquals(listOf("r0", "r1", "r2"), q.items.map { it.id }) // persisted item ids preserved
        assertEquals(2, q.currentIndex)
        assertEquals("C", q.current?.track?.title)
        assertEquals(RepeatMode.ALL, q.repeatMode)
    }

    @Test
    fun `restore clamps an out-of-range cursor`() {
        val repo = repo()
        repo.restore(
            listOf(queueItem("r0", "A"), queueItem("r1", "B")),
            currentIndex = 9,
            repeatMode = RepeatMode.OFF,
        )

        assertEquals(1, repo.state.value.currentIndex)
    }

    @Test
    fun `setQueue replaces the queue positioned at the given index with the context`() {
        val repo = repo()
        repo.addToQueue(tracks("old")) // any prior content is discarded by setQueue

        repo.setQueue(tracks("A", "B", "C"), startIndex = 2, context = QueueContext(QueueSourceKind.ALBUM, "Discovery"))

        val q = repo.state.value
        assertEquals(listOf("A", "B", "C"), q.items.map { it.track.title })
        assertEquals(2, q.currentIndex)
        assertEquals("C", q.current?.track?.title)
        assertEquals(QueueSourceKind.ALBUM, q.context.kind)
        assertEquals("Discovery", q.context.label)
    }

    @Test
    fun `setQueue clamps an out-of-range index and ignores an empty list`() {
        val repo = repo()
        repo.setQueue(tracks("A", "B"), startIndex = 9, context = QueueContext())
        assertEquals(1, repo.state.value.currentIndex)

        repo.setQueue(emptyList(), 0, QueueContext(QueueSourceKind.RADIO, "Radio"))

        // A no-op empty setQueue must not wipe the current queue.
        assertEquals(listOf("A", "B"), repo.state.value.items.map { it.track.title })
    }

    @Test
    fun `restore ignores an empty item list`() {
        val repo = repo()
        repo.addToQueue(tracks("A"))

        repo.restore(emptyList(), currentIndex = 0, repeatMode = RepeatMode.ALL)

        // A no-op restore must not wipe the current queue.
        assertEquals(listOf("A"), repo.state.value.items.map { it.track.title })
    }
}
