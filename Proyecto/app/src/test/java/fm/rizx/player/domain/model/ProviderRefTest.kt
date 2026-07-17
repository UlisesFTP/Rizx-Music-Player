package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProviderRefTest {

    @Test
    fun identityKey_is_provider_colon_id() {
        assertEquals("youtube:abc123", ProviderRef("youtube", "abc123").identityKey)
    }

    @Test
    fun equality_and_hashcode_ignore_url() {
        val a = ProviderRef("youtube", "abc", url = "https://a/1")
        val b = ProviderRef("youtube", "abc", url = "https://b/2")
        val c = ProviderRef("youtube", "abc", url = null)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.hashCode(), c.hashCode())
    }

    @Test
    fun differing_provider_or_id_are_not_equal() {
        val base = ProviderRef("youtube", "abc")
        assertNotEquals(base, ProviderRef("soundcloud", "abc"))
        assertNotEquals(base, ProviderRef("youtube", "xyz"))
    }

    @Test
    fun usable_as_map_key_by_identity_only() {
        val map = hashMapOf(ProviderRef("local", "1", url = "file:///a") to "track")
        assertEquals("track", map[ProviderRef("local", "1")])
        assertEquals("track", map[ProviderRef("local", "1", url = "file:///other")])
    }

    @Test
    fun copy_preserves_identity_and_can_change_url() {
        val base = ProviderRef("youtube", "abc", url = "https://a")
        val recopied = base.copy(url = "https://b")
        assertEquals(base, recopied) // identity unchanged
        assertEquals("https://b", recopied.url)
    }
}
