package fm.rizx.player.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class EnabledProviderStoreTest {

    private fun kotlinx.coroutines.CoroutineScope.newStore(): DataStore<Preferences> {
        val dir = Files.createTempDirectory("rizx-enabled").toFile()
        return PreferenceDataStoreFactory.create(scope = this) { java.io.File(dir, "settings.preferences_pb") }
    }

    @Test
    fun `providers default to enabled`() = runTest {
        val store = EnabledProviderStoreImpl(backgroundScope.newStore())

        assertEquals(true, store.isEnabled("deezer-dashboard").first())
    }

    @Test
    fun `enabled state persists and reads back`() = runTest {
        val store = EnabledProviderStoreImpl(backgroundScope.newStore())

        store.setEnabled("deezer-dashboard", false)
        assertEquals(false, store.isEnabled("deezer-dashboard").first())
        store.setEnabled("deezer-dashboard", true)
        assertEquals(true, store.isEnabled("deezer-dashboard").first())
    }

    @Test
    fun `snapshot defaults unset ids to enabled`() = runTest {
        val store = EnabledProviderStoreImpl(backgroundScope.newStore())
        store.setEnabled("off", false)

        val snap = store.snapshot(listOf("off", "unset"))

        assertEquals(false, snap["off"])
        assertEquals(true, snap["unset"])
    }
}
