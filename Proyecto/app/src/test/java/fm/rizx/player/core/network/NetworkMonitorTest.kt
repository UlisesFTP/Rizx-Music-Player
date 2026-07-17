package fm.rizx.player.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure test of the bad-signal threshold (the network read itself needs a device/emulator). */
class NetworkMonitorTest {

    @Test
    fun `isBadSignal flags a known-low downstream estimate only`() {
        assertTrue(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 1_500).isBadSignal)
        assertFalse(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 8_000).isBadSignal) // strong
        assertFalse(NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 0).isBadSignal) // unknown ≠ bad
    }
}
