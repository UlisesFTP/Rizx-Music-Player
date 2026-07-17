package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueTest {

    private val track = Track(title = "T", source = ProviderRef("p", "1"))

    @Test
    fun same_track_can_appear_with_distinct_queueitem_ids() {
        val a = QueueItem(id = "q1", track = track, addedAtIso = "2026-01-01T00:00:00Z")
        val b = QueueItem(id = "q2", track = track, addedAtIso = "2026-01-01T00:00:01Z")
        assertNotEquals(a.id, b.id)
        // QueueItem identity differs even though it is the same underlying track.
        assertEquals(a.track.source, b.track.source)
    }

    @Test
    fun current_returns_item_at_index_or_null() {
        val item = QueueItem(id = "q1", track = track, addedAtIso = "t")
        assertEquals(item, PlaybackQueue(listOf(item), currentIndex = 0).current)
        assertNull(PlaybackQueue(listOf(item), currentIndex = -1).current)
        assertNull(PlaybackQueue(emptyList(), currentIndex = 0).current)
    }
}
