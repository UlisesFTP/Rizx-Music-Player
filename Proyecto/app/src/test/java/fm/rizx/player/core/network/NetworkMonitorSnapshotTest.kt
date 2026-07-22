package fm.rizx.player.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metered flag gates background downloading, so its default matters: an unknown network must never
 * read as free.
 */
class NetworkMonitorSnapshotTest {

    @Test
    fun `an unknown network is not treated as unmetered`() {
        assertFalse(NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 0).isUnmetered)
    }

    @Test
    fun `metered and cellular are independent`() {
        // A phone hotspot is Wi-Fi transport on a metered plan; home Wi-Fi is neither cellular nor metered.
        val hotspot = NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 20_000, isUnmetered = false)
        val home = NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 50_000, isUnmetered = true)

        assertFalse(hotspot.isUnmetered)
        assertTrue(home.isUnmetered)
    }

    @Test
    fun `bad signal only flags a known low estimate`() {
        assertFalse(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 0).isBadSignal)
        assertTrue(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 500).isBadSignal)
        assertFalse(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 50_000).isBadSignal)
    }
}
