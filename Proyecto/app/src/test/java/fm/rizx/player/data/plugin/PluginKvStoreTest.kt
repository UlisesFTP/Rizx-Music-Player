package fm.rizx.player.data.plugin

import fm.rizx.player.data.plugin.install.PluginInstaller
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Per-plugin settings/storage. The point of this store is that a plugin's token survives a restart
 * (and an update) without ever landing in the app's shared preferences — so what is asserted here is
 * where the bytes go, not just that a getter round-trips.
 */
class PluginKvStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(root: File = tmp.root) = PluginKvStore(root, Json)

    @Test
    fun `a value round-trips and lands in the plugin's own directory`() {
        val kv = store()
        kv.set("acme", PluginKvStore.SCOPE_SETTINGS, "token", "\"abc\"")

        assertEquals("\"abc\"", kv.get("acme", PluginKvStore.SCOPE_SETTINGS, "token"))
        val file = File(File(tmp.root, "acme"), PluginInstaller.SETTINGS_FILE)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("token"))
    }

    @Test
    fun `settings and storage are separate files, not one bag`() {
        val kv = store()
        kv.set("acme", PluginKvStore.SCOPE_SETTINGS, "k", "1")
        kv.set("acme", PluginKvStore.SCOPE_STORAGE, "k", "2")

        assertEquals("1", kv.get("acme", PluginKvStore.SCOPE_SETTINGS, "k"))
        assertEquals("2", kv.get("acme", PluginKvStore.SCOPE_STORAGE, "k"))
        assertTrue(File(File(tmp.root, "acme"), PluginInstaller.STORAGE_FILE).isFile)
    }

    @Test
    fun `two plugins cannot read each other's values`() {
        val kv = store()
        kv.set("acme", PluginKvStore.SCOPE_STORAGE, "secret", "\"a\"")

        assertNull(kv.get("other", PluginKvStore.SCOPE_STORAGE, "secret"))
    }

    @Test
    fun `values survive a fresh store over the same directory — the restart case`() {
        store().set("acme", PluginKvStore.SCOPE_SETTINGS, "token", "\"abc\"")

        assertEquals("\"abc\"", store().get("acme", PluginKvStore.SCOPE_SETTINGS, "token"))
    }

    @Test
    fun `remove deletes just that key`() {
        val kv = store()
        kv.set("acme", PluginKvStore.SCOPE_STORAGE, "a", "1")
        kv.set("acme", PluginKvStore.SCOPE_STORAGE, "b", "2")

        kv.remove("acme", PluginKvStore.SCOPE_STORAGE, "a")

        assertNull(kv.get("acme", PluginKvStore.SCOPE_STORAGE, "a"))
        assertEquals("2", kv.get("acme", PluginKvStore.SCOPE_STORAGE, "b"))
    }

    @Test
    fun `a corrupt file reads as empty instead of throwing`() {
        val dir = File(tmp.root, "acme").apply { mkdirs() }
        File(dir, PluginInstaller.SETTINGS_FILE).writeText("{ not json")

        assertNull(store().get("acme", PluginKvStore.SCOPE_SETTINGS, "anything"))
    }

    @Test
    fun `keys needing escaping survive the hand-rolled encoding`() {
        val kv = store()
        kv.set("acme", PluginKvStore.SCOPE_STORAGE, """weird "key" \ here""", """{"nested":"value"}""")

        assertEquals(
            """{"nested":"value"}""",
            store().get("acme", PluginKvStore.SCOPE_STORAGE, """weird "key" \ here"""),
        )
    }
}
