package fm.rizx.player.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `parses tags with and without the v, and fills missing parts with zero`() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3"))
        assertEquals(SemanticVersion(1, 2, 0), SemanticVersion.parse("1.2"))
        assertEquals(SemanticVersion(2, 0, 0), SemanticVersion.parse("V2"))
        assertEquals(SemanticVersion(1, 0, 0, listOf("rc", "1")), SemanticVersion.parse("v1.0.0-rc.1"))
        assertEquals(SemanticVersion(1, 0, 0), SemanticVersion.parse("1.0.0+build.7"))
    }

    @Test
    fun `refuses things that are not versions`() {
        assertNull(SemanticVersion.parse("latest"))
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("release-1"))
        assertNull(SemanticVersion.parse("1.2.3.4"))
    }

    @Test
    fun `orders numerically, not lexically`() {
        assertTrue(SemanticVersion.parse("1.10.0")!! > SemanticVersion.parse("1.9.9")!!)
        assertTrue(SemanticVersion.parse("2.0.0")!! > SemanticVersion.parse("1.99.99")!!)
        assertTrue(SemanticVersion.parse("1.0.1")!! > SemanticVersion.parse("1.0.0")!!)
    }

    @Test
    fun `a pre-release sorts before its release and identifiers compare left to right`() {
        assertTrue(SemanticVersion.parse("1.1.0")!! > SemanticVersion.parse("1.1.0-rc.1")!!)
        assertTrue(SemanticVersion.parse("1.1.0-rc.2")!! > SemanticVersion.parse("1.1.0-rc.1")!!)
        assertTrue(SemanticVersion.parse("1.1.0-rc.10")!! > SemanticVersion.parse("1.1.0-rc.9")!!)
        assertTrue(SemanticVersion.parse("1.1.0-beta")!! > SemanticVersion.parse("1.1.0-alpha")!!)
        assertTrue(SemanticVersion.parse("1.1.0-alpha.1")!! > SemanticVersion.parse("1.1.0-alpha")!!)
    }

    @Test
    fun `isNewer is strict and never true for unparseable input`() {
        assertTrue(SemanticVersion.isNewer("v1.0.1", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("1.0.0", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("0.9.9", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("latest", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("1.0.1", "garbage"))
    }

    @Test
    fun `prints the canonical form`() {
        assertEquals("1.2.0", SemanticVersion.parse("v1.2")!!.toString())
        assertEquals("1.2.0-rc.1", SemanticVersion.parse("1.2.0-rc.1")!!.toString())
    }
}
