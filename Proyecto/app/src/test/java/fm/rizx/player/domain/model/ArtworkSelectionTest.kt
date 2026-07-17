package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArtworkSelectionTest {

    @Test
    fun null_or_empty_set_returns_null() {
        assertNull((null as ArtworkSet?).pick(ArtworkPurpose.COVER, ArtworkTargetPx.COVER))
        assertNull(ArtworkSet(emptyList()).pick(ArtworkPurpose.COVER, ArtworkTargetPx.COVER))
    }

    @Test
    fun falls_back_to_first_when_no_candidates() {
        // Both items have a non-matching purpose, so neither is a candidate for COVER ->
        // fall back to items.first(), even though its url is empty.
        val first = Artwork(url = "", purpose = ArtworkPurpose.AVATAR)
        val set = ArtworkSet(listOf(first, Artwork(url = "u", purpose = ArtworkPurpose.THUMBNAIL)))
        assertSame(first, set.pick(ArtworkPurpose.COVER, ArtworkTargetPx.COVER))
    }

    @Test
    fun no_purpose_items_are_always_candidates() {
        val noPurpose = Artwork(url = "n", width = 510, height = 510)
        assertEquals(noPurpose, ArtworkSet(listOf(noPurpose)).pick(ArtworkPurpose.COVER, 512))
    }

    @Test
    fun prefers_closest_size_for_cover() {
        val small = Artwork(url = "s", width = 64, height = 64, purpose = ArtworkPurpose.COVER)
        val good = Artwork(url = "g", width = 500, height = 500, purpose = ArtworkPurpose.COVER)
        assertEquals(good, ArtworkSet(listOf(small, good)).pick(ArtworkPurpose.COVER, 512))
    }

    @Test
    fun penalizes_heavy_upscale() {
        // tiny (needs 8x upscale to 512) loses to a no-purpose 480px image (only 1.07x).
        val tiny = Artwork(url = "t", width = 64, height = 64, purpose = ArtworkPurpose.COVER)
        val ok = Artwork(url = "o", width = 480, height = 480)
        assertEquals(ok, ArtworkSet(listOf(tiny, ok)).pick(ArtworkPurpose.COVER, 512))
    }

    @Test
    fun background_prefers_wide_aspect() {
        val square = Artwork(url = "s", width = 1080, height = 1080, purpose = ArtworkPurpose.BACKGROUND)
        val wide = Artwork(url = "w", width = 1920, height = 1080, purpose = ArtworkPurpose.BACKGROUND)
        assertEquals(wide, ArtworkSet(listOf(square, wide)).pick(ArtworkPurpose.BACKGROUND, ArtworkTargetPx.BACKGROUND))
    }
}
