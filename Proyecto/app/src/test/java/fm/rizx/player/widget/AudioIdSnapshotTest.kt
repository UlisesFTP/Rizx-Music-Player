package fm.rizx.player.widget

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionHistoryItem
import fm.rizx.player.domain.recognition.RecognitionMatch
import fm.rizx.player.domain.recognition.RecognitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioIdSnapshotTest {

    private val match = RecognitionMatch(
        provider = "shazam", providerTrackId = "1", title = "Hurt", artist = "Nine Inch Nails",
        album = "The Downward Spiral", artworkUrl = "https://img/small.jpg", artworkHqUrl = "https://img/hq.jpg",
    )
    private val track = Track(title = "Hurt", source = ProviderRef("deezer", "1"))
    private val history = RecognitionHistoryItem("h1", match, track, "2026-08-25T18:00:00Z")

    @Test
    fun `idle shows the last identified song, playable when it was resolved`() {
        val snapshot = AudioIdSnapshot.of(RecognitionState.Idle, history)

        assertEquals(AudioIdPhase.IDLE, snapshot.phase)
        assertEquals("Hurt", snapshot.title)
        assertEquals("Nine Inch Nails", snapshot.artist)
        assertEquals("The Downward Spiral", snapshot.album)
        assertEquals("the sharper cover wins", "https://img/hq.jpg", snapshot.artworkUrl)
        assertTrue(snapshot.canPlay)
        assertTrue(snapshot.hasResult)
    }

    @Test
    fun `nothing identified yet is the empty card`() {
        val snapshot = AudioIdSnapshot.of(RecognitionState.Idle, null)

        assertEquals(AudioIdSnapshot.EMPTY, snapshot)
        assertFalse(snapshot.hasResult)
        assertFalse(snapshot.canPlay)
    }

    @Test
    fun `a live match beats the history and an unresolved one cannot be played`() {
        val live = match.copy(title = "Closer", album = null)
        val snapshot = AudioIdSnapshot.of(RecognitionState.Matched(live, resolvedTrack = null), history)

        assertEquals(AudioIdPhase.MATCHED, snapshot.phase)
        assertEquals("Closer", snapshot.title)
        assertNull(snapshot.album)
        assertFalse("no playable track behind it", snapshot.canPlay)
    }

    @Test
    fun `the phases while the app listens carry through, keeping the last song underneath`() {
        assertEquals(AudioIdPhase.LISTENING, AudioIdSnapshot.of(RecognitionState.Listening(1_000L, 0.4f), history).phase)
        assertEquals(AudioIdPhase.IDENTIFYING, AudioIdSnapshot.of(RecognitionState.Processing, history).phase)
        assertEquals(AudioIdPhase.NO_MATCH, AudioIdSnapshot.of(RecognitionState.NoMatch, history).phase)
        val failed = AudioIdSnapshot.of(RecognitionState.Failed(RecognitionError.values().first(), retryable = true), history)
        assertEquals(AudioIdPhase.FAILED, failed.phase)
        assertEquals("the last song is still known", "Hurt", failed.title)
    }
}
