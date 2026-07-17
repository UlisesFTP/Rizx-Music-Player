package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {

    private fun track(src: ProviderRef, candidates: List<StreamCandidate> = emptyList()) = Track(
        title = "Velvet Asphalt",
        artists = listOf(ArtistCredit("Maya Sol")),
        source = src,
        streamCandidates = candidates,
    )

    @Test
    fun stripResolutionState_clears_only_stream_candidates() {
        val src = ProviderRef("youtube", "v1")
        val candidate = StreamCandidate(id = "c1", title = "v", source = src)
        val t = track(src, listOf(candidate))

        val stripped = t.stripResolutionState()

        assertTrue(stripped.streamCandidates.isEmpty())
        // Everything else is untouched.
        assertEquals(t.copy(streamCandidates = emptyList()), stripped)
    }

    @Test
    fun identity_is_source_not_structural_fields() {
        val a = track(ProviderRef("youtube", "v1")).copy(title = "A")
        val b = track(ProviderRef("youtube", "v1", url = "http://x")).copy(title = "B")
        // Different titles/urls, same upstream identity.
        assertEquals(a.source, b.source)
        assertEquals(a.source.identityKey, b.source.identityKey)
    }
}
