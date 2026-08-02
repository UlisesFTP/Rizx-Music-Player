package fm.rizx.player.domain.canvas

import fm.rizx.player.domain.model.CanvasBlockReason
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Whether a canvas may be fetched at all — the rule that has to run *before* any bytes are spent. */
class CanvasGateTest {

    private val on = CanvasPreferences(enabled = true)
    private val wifi = CanvasGate.Conditions(unmetered = true)
    private val mobile = CanvasGate.Conditions(unmetered = false)

    @Test
    fun `off means off — nothing is looked up`() {
        assertEquals(
            CanvasBlockReason.DISABLED,
            CanvasGate.blockedBy(CanvasPreferences(enabled = false), wifi),
        )
    }

    @Test
    fun `on wifi with the default policy, it is allowed`() {
        assertNull(CanvasGate.blockedBy(on, wifi))
    }

    @Test
    fun `mobile data is blocked by default`() {
        assertEquals(CanvasBlockReason.METERED, CanvasGate.blockedBy(on, mobile))
    }

    @Test
    fun `a phone hotspot is mobile data, whatever the radio says`() {
        // The trap this whole field exists for: a hotspot reports Wi-Fi transport while billing the
        // user's plan. Keying on "is cellular" — which the first version of the canvas did — lets it
        // quietly spend someone's allowance.
        val hotspot = CanvasGate.Conditions(unmetered = false)
        assertEquals(CanvasBlockReason.METERED, CanvasGate.blockedBy(on, hotspot))
    }

    @Test
    fun `allowing mobile data lets it through`() {
        val anyNetwork = on.copy(network = CanvasNetworkPolicy.ANY)
        assertNull(CanvasGate.blockedBy(anyNetwork, mobile))
    }

    @Test
    fun `data saver wins over allowing mobile data, and says so by name`() {
        // Someone who asked to spend less did not mean "except for decoration". The reason is
        // DATA_SAVER rather than METERED so the diagnostics name the switch they can actually turn off.
        val anyNetwork = on.copy(network = CanvasNetworkPolicy.ANY)
        assertEquals(
            CanvasBlockReason.DATA_SAVER,
            CanvasGate.blockedBy(anyNetwork, mobile.copy(dataSaver = true)),
        )
    }

    @Test
    fun `data saver blocks it on wifi too`() {
        // It used to require a metered link, which meant the switch did nothing at home — including on
        // the Wi-Fi somebody pays for by the gigabyte. A second video stream is the most expensive
        // optional thing here, so it is the first to go on any connection.
        assertEquals(CanvasBlockReason.DATA_SAVER, CanvasGate.blockedBy(on, wifi.copy(dataSaver = true)))
    }

    @Test
    fun `data saver is reported ahead of battery saver, being the wider rule`() {
        assertEquals(
            CanvasBlockReason.DATA_SAVER,
            CanvasGate.blockedBy(on, wifi.copy(dataSaver = true, powerSaveMode = true)),
        )
    }

    @Test
    fun `with nothing saving, wifi still allows it`() {
        assertNull(CanvasGate.blockedBy(on, wifi))
    }

    @Test
    fun `battery saver blocks it, and is checked before the network`() {
        // Before the network, so the reason shown is the one the user can act on most directly.
        assertEquals(
            CanvasBlockReason.BATTERY_SAVER,
            CanvasGate.blockedBy(on, mobile.copy(powerSaveMode = true)),
        )
    }

    @Test
    fun `unless the user explicitly allowed it`() {
        assertNull(CanvasGate.blockedBy(on.copy(allowOnBatterySaver = true), wifi.copy(powerSaveMode = true)))
    }

    @Test
    fun `a link too weak for a second stream is left alone`() {
        assertEquals(
            CanvasBlockReason.WEAK_SIGNAL,
            CanvasGate.blockedBy(on, wifi.copy(badSignal = true)),
        )
    }

    @Test
    fun `the chosen quality is honoured on an unmetered link`() {
        assertEquals(CanvasQuality.HIGH, CanvasGate.quality(CanvasQuality.HIGH, wifi))
        assertEquals(CanvasQuality.AUTO, CanvasGate.quality(CanvasQuality.AUTO, wifi))
    }

    @Test
    fun `quality steps down on mobile data and on a low-RAM device, whatever was chosen`() {
        assertEquals(CanvasQuality.DATA_SAVER, CanvasGate.quality(CanvasQuality.HIGH, mobile))
        assertEquals(
            CanvasQuality.DATA_SAVER,
            CanvasGate.quality(CanvasQuality.HIGH, wifi.copy(lowRamDevice = true)),
        )
    }
}
