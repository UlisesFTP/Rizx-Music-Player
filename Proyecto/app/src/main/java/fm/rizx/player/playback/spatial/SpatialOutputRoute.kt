package fm.rizx.player.playback.spatial

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers one question, live: is the music going to a pair of ears, or to the room?
 *
 * The spatializer is built entirely on interaural cues — a delay to one ear, a little less treble on
 * that side. Those only exist when each ear receives its own channel. Out of a phone's speaker both
 * channels arrive at both ears milliseconds apart anyway, so the same processing stops sounding like
 * space and starts sounding like a phasing effect on somebody's record. So the effect waits instead.
 *
 * **Two answers, honestly labelled.** From API 33 the platform will say where it would actually route
 * a media stream, which is exact. Below that there is no such API, so this falls back to "is anything
 * better than the speaker plugged in" — which matches how Android's own routing policy chooses, and is
 * right in every ordinary case.
 */
@Singleton
class SpatialOutputRoute @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Emits true while the audio is going somewhere with two separate ears at the end of it. */
    val headphonesConnected: Flow<Boolean> = callbackFlow {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        trySend(query(audio))
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(query(audio))
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(query(audio))
            }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { runCatching { audio.unregisterAudioDeviceCallback(callback) } }
    }.distinctUntilChanged()

    private fun query(audio: AudioManager): Boolean = runCatching {
        val devices = if (Build.VERSION.SDK_INT >= 33) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audio.getAudioDevicesForAttributes(attributes)
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        devices.any { it.type in PRIVATE_OUTPUTS }
    }.getOrDefault(false)

    private companion object {
        /** Outputs where the two channels reach two different ears. */
        val PRIVATE_OUTPUTS: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            // BLE Audio is API 31+; as bare ints these would simply never match, but adding them
            // conditionally also keeps lint's NewApi sweep clean.
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
            }
        }
    }
}
