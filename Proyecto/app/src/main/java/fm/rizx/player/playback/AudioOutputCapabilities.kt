package fm.rizx.player.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what the device's audio output can actually do, so the Settings screen can show it next to the
 * Hi-Res toggle. Pure framework calls (no permission needed to enumerate output devices). The label
 * formatting is a pure function ([formatOutputLabel]) so it unit-tests on the JVM; the framework query
 * ([query]) stays thin and is device-verified.
 *
 * Caveat: the native sample rate is the mixer rate — AudioFlinger/the HAL may still resample below the
 * app — so this is informational, not a promise of bit-perfect output.
 */
@Singleton
class AudioOutputCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Info(
        val nativeSampleRateHz: Int?,
        val maxSampleRateHz: Int?,
        val floatCapable: Boolean,
    )

    /** Query the current output sinks. Safe to call on the main thread (a couple of cheap framework calls). */
    fun query(): Info {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Info(null, null, false)
        val native = runCatching {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull()
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()?.toList().orEmpty()
        // Real playback sinks only (skip telephony/aux/etc.); fall back to everything if the filter is empty.
        val sinks = devices.filter { it.type in RELEVANT_OUTPUTS }.ifEmpty { devices }
        // getSampleRates()/getEncodings() may be empty ("unspecified") on some devices → treat as unknown.
        val maxRate = sinks.flatMap { it.sampleRates.toList() }.maxOrNull()
        val floatCapable = sinks.any { AudioFormat.ENCODING_PCM_FLOAT in it.encodings }
        return Info(nativeSampleRateHz = native, maxSampleRateHz = maxRate, floatCapable = floatCapable)
    }

    /** A one-line description of the output capability, e.g. `"48 kHz output · up to 192 kHz · 32-bit float"`. */
    fun describe(): String = query().let { formatOutputLabel(it.nativeSampleRateHz, it.maxSampleRateHz, it.floatCapable) }

    private companion object {
        val RELEVANT_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}

/**
 * Pure formatter for [AudioOutputCapabilities.Info]. Shows the native output rate, an "up to" max only when
 * it exceeds the native rate, and a float-capable note. Kept top-level + `internal` so it's JVM-testable.
 */
internal fun formatOutputLabel(nativeHz: Int?, maxHz: Int?, floatCapable: Boolean): String {
    if (nativeHz == null && maxHz == null) return "Output capability unknown"
    val parts = buildList {
        nativeHz?.let { add("${khz(it)} output") }
        if (maxHz != null && (nativeHz == null || maxHz > nativeHz)) add("up to ${khz(maxHz)}")
        if (floatCapable) add("32-bit float")
    }
    return parts.joinToString(" · ")
}

/** Formats a sample rate in Hz as kHz: 48000 → "48 kHz", 44100 → "44.1 kHz" (locale-independent). */
private fun khz(hz: Int): String =
    if (hz % 1000 == 0) {
        "${hz / 1000} kHz"
    } else {
        val tenths = (hz + 50) / 100 // round to the nearest 0.1 kHz, expressed in tenths of a kHz
        "${tenths / 10}.${tenths % 10} kHz"
    }
