package fm.rizx.player.core.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionResolverTest {

    @Test
    fun `walks the chain past blanks and normalizes the first hit`() {
        val resolver = RegionResolver(listOf({ null }, { "  " }, { " MX " }, { "us" }))

        assertEquals("mx", resolver.country())
    }

    @Test
    fun `rejects junk and throwing suppliers, falling through to null (= global)`() {
        val resolver = RegionResolver(
            listOf({ "" }, { "USA" }, { "1x" }, { throw IllegalStateException("no telephony") }),
        )

        assertNull(resolver.country())
        assertNull(resolver.countryDisplayName())
    }

    @Test
    fun `display name resolves for a known code`() {
        val name = RegionResolver(listOf { "mx" }).countryDisplayName()

        assertTrue(!name.isNullOrBlank())
    }
}
