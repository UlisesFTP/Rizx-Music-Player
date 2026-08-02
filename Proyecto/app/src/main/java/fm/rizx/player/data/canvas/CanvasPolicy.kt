package fm.rizx.player.data.canvas

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.domain.canvas.CanvasGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Samples the device for [CanvasGate] — the network, the battery mode and the RAM class.
 *
 * Everything is read on demand. There is no callback registration, no listener to leak and no state to go
 * stale: the reading is taken at the instant the decision matters, exactly like [NetworkMonitor] itself.
 *
 * The rule this feeds lives in the domain, so the interesting part is testable without an emulator; this
 * class deliberately contains no decisions of its own.
 */
@Singleton
class CanvasPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: NetworkMonitor,
) : CanvasGate.Sampler {
    /** A snapshot of everything the gate needs. */
    override fun sample(dataSaver: Boolean): CanvasGate.Conditions {
        val net = networkMonitor.snapshot()
        return CanvasGate.Conditions(
            // isUnmetered, not isCellular. A phone hotspot reports Wi-Fi transport while billing the
            // user's data plan; keying on the radio would let the canvas quietly spend someone's
            // allowance. NetworkMonitor already documents this — the canvas was the caller ignoring it.
            unmetered = net.isUnmetered,
            badSignal = net.isBadSignal,
            powerSaveMode = context.getSystemService<PowerManager>()?.isPowerSaveMode == true,
            lowRamDevice = context.getSystemService<ActivityManager>()?.isLowRamDevice == true,
            dataSaver = dataSaver,
        )
    }
}
