package fm.rizx.player.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure test of the bad-signal threshold (the network read itself needs a device/emulator). */
class NetworkMonitorTest {

    @Test
    fun `isBadSignal flags a known-low downstream estimate only`() {
        assertTrue(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 400).isBadSignal)
        assertFalse(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 8_000).isBadSignal) // strong
        assertFalse(NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 0).isBadSignal) // unknown ≠ bad
    }

    @Test
    fun `an ordinary link is not weak — the threshold sits near what the audio actually needs`() {
        // The old 3 Mbps bar mislabelled everyday Wi-Fi and LTE as "weak", which quietly served the
        // worst stream to people who never asked to save data. 160 kbps audio needs nothing like that.
        assertFalse(NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 1_500).isBadSignal)
        assertFalse(NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 2_000).isBadSignal)
    }
}
