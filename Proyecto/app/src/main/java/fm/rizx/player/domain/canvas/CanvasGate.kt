package fm.rizx.player.domain.canvas

import fm.rizx.player.domain.model.CanvasBlockReason
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasQuality

/**
 * The rule that decides whether a canvas may be fetched — separated from the sensors that feed it.
 *
 * Pure Kotlin so the decision itself can be tested exhaustively without a device: every combination of
 * metered, power-save, weak signal and data saver is one call. The Android side (`CanvasPolicy`) does
 * nothing but sample `ConnectivityManager`, `PowerManager` and `ActivityManager` and hand the readings
 * here, which keeps the interesting logic out of the part that needs an emulator to run.
 */
object CanvasGate {

    /**
     * Samples the device. Implemented by `CanvasPolicy`, which is the only part that needs Android —
     * keeping it behind an interface is what lets every branch of [blockedBy] be tested on the JVM.
     */
    fun interface Sampler {
        fun sample(dataSaver: Boolean): Conditions
    }

    /** What the device looks like right now, as far as this decision cares. */
    data class Conditions(
        /** The connection costs nothing to use. **Not** "is Wi-Fi" — a hotspot is metered Wi-Fi. */
        val unmetered: Boolean,
        val badSignal: Boolean = false,
        val powerSaveMode: Boolean = false,
        val lowRamDevice: Boolean = false,
        /**
         * Data saving is in force — Rizx's own switch or Android's. Network-independent by design;
         * `DataSaverState` is what folds the two switches into this one boolean.
         */
        val dataSaver: Boolean = false,
    )

    /** `null` when a canvas is allowed; otherwise why it isn't. */
    fun blockedBy(preferences: CanvasPreferences, conditions: Conditions): CanvasBlockReason? {
        if (!preferences.enabled) return CanvasBlockReason.DISABLED
        // Before the network checks, and on **any** connection. A second video stream on top of the audio
        // is the most expensive optional thing this app does, so it is the first thing data saving turns
        // off — and it used to require a metered link, which meant the switch did nothing on the Wi-Fi
        // that someone might well be paying for by the gigabyte.
        if (conditions.dataSaver) return CanvasBlockReason.DATA_SAVER
        if (conditions.powerSaveMode && !preferences.allowOnBatterySaver) {
            return CanvasBlockReason.BATTERY_SAVER
        }
        if (!conditions.unmetered && preferences.network == CanvasNetworkPolicy.UNMETERED_ONLY) {
            return CanvasBlockReason.METERED
        }
        if (conditions.badSignal) return CanvasBlockReason.WEAK_SIGNAL
        return null
    }

    /**
     * How big a video to ask for: what the user chose, clamped by what the device and the link can
     * sensibly carry.
     *
     * This used to be derived outright, on the reasoning that YouTube serves one muxed stream for most
     * videos so a picker would change nothing. Apple's motion artwork made the choice real — one HLS URL
     * holds a ladder from 360² to 2160², and the cap is what decides which rung plays.
     *
     * The clamp is not a preference being overruled for its own sake: a metered link is the user's money
     * and a low-RAM device is a second video decoder competing with the audio one. Both take
     * precedence over "make it pretty".
     */
    fun quality(preferred: CanvasQuality, conditions: Conditions): CanvasQuality =
        if (!conditions.unmetered || conditions.lowRamDevice) CanvasQuality.DATA_SAVER else preferred
}
