package fm.rizx.player.data.provider

import fm.rizx.player.domain.provider.ProviderDescriptor
import fm.rizx.player.domain.provider.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderRegistryTest {

    private data class TestProvider(
        override val id: String,
        override val kind: ProviderKind,
        override val name: String = id,
    ) : ProviderDescriptor

    private lateinit var registry: DefaultProviderRegistry

    @Before
    fun setup() {
        registry = DefaultProviderRegistry()
    }

    private fun meta(id: String) = TestProvider(id, ProviderKind.METADATA)
    private fun stream(id: String) = TestProvider(id, ProviderKind.STREAMING)

    @Test
    fun `register returns the provider id`() {
        assertEquals("m1", registry.register(meta("m1")))
    }

    @Test
    fun `registering the first provider for a kind makes it active`() {
        registry.register(meta("m1"))
        assertEquals("m1", registry.getActive(ProviderKind.METADATA))
        assertNull(registry.getActive(ProviderKind.STREAMING))
    }

    @Test
    fun `registering a second provider does not override the active one`() {
        registry.register(meta("m1"))
        registry.register(meta("m2"))
        assertEquals("m1", registry.getActive(ProviderKind.METADATA))
        assertEquals(2, registry.list(ProviderKind.METADATA).size)
    }

    @Test
    fun `active provider can be changed`() {
        registry.register(meta("m1"))
        registry.register(meta("m2"))
        registry.setActive(ProviderKind.METADATA, "m2")
        assertEquals("m2", registry.getActive(ProviderKind.METADATA))
    }

    @Test
    fun `removing the active provider falls back to another of the same kind`() {
        registry.register(meta("m1")) // active
        registry.register(meta("m2"))
        assertTrue(registry.unregister("m1"))
        assertEquals("m2", registry.getActive(ProviderKind.METADATA))
    }

    @Test
    fun `removing the last provider clears the active selection`() {
        registry.register(meta("m1"))
        assertTrue(registry.unregister("m1"))
        assertNull(registry.getActive(ProviderKind.METADATA))
        assertTrue(registry.list(ProviderKind.METADATA).isEmpty())
    }

    @Test
    fun `unregister returns false for an unknown id`() {
        assertFalse(registry.unregister("nope"))
    }

    @Test
    fun `kinds are independent`() {
        registry.register(meta("m1"))
        registry.register(stream("s1"))
        assertEquals("m1", registry.getActive(ProviderKind.METADATA))
        assertEquals("s1", registry.getActive(ProviderKind.STREAMING))
    }

    @Test
    fun `get is kind-checked`() {
        registry.register(meta("m1"))
        assertEquals("m1", registry.get("m1", ProviderKind.METADATA)?.id)
        assertNull(registry.get("m1", ProviderKind.STREAMING)) // wrong kind
        assertNull(registry.get("missing", ProviderKind.METADATA))
        assertNull(registry.get(null, ProviderKind.METADATA))
    }

    @Test
    fun `setActive throws for a provider not registered under that kind`() {
        registry.register(meta("m1"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.setActive(ProviderKind.STREAMING, "m1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.setActive(ProviderKind.METADATA, "ghost")
        }
    }

    @Test
    fun `activeDescriptor returns the active descriptor`() {
        val m1 = meta("m1")
        registry.register(m1)
        assertEquals(m1, registry.activeDescriptor(ProviderKind.METADATA))
        assertNull(registry.activeDescriptor(ProviderKind.STREAMING))
    }

    @Test
    fun `clear removes everything and clears active selections`() {
        registry.register(meta("m1"))
        registry.register(stream("s1"))
        registry.clear()
        assertTrue(registry.list().isEmpty())
        assertNull(registry.getActive(ProviderKind.METADATA))
        assertNull(registry.getActive(ProviderKind.STREAMING))
    }

    @Test
    fun `fake providers register under their declared kinds`() {
        registry.register(FakeMetadataProvider())
        registry.register(FakeStreamingProvider())
        assertEquals(FakeMetadataProvider.ID, registry.getActive(ProviderKind.METADATA))
        assertEquals(FakeStreamingProvider.ID, registry.getActive(ProviderKind.STREAMING))
    }
}
