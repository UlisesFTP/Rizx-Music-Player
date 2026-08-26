package fm.rizx.player.domain.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionInboxTest {

    @Test
    fun `empty until offered, taken exactly once`() {
        val inbox = RecognitionInbox()
        assertEquals(0L, inbox.pending.value)
        assertFalse(inbox.consume())

        inbox.offer()
        assertNotEquals(0L, inbox.pending.value)
        assertTrue(inbox.consume())
        assertEquals(0L, inbox.pending.value)
        assertFalse("nothing acts on it twice", inbox.consume())
    }

    @Test
    fun `a second tap is a new request, not the old one again`() {
        val inbox = RecognitionInbox()
        inbox.offer()
        val first = inbox.pending.value
        inbox.offer()
        assertNotEquals(first, inbox.pending.value)
        assertTrue(inbox.consume())
        assertFalse(inbox.consume())
    }
}
