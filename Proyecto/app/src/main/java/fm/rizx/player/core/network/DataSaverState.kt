package fm.rizx.player.core.network

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that decides whether the app is trying to save data, and what that costs.
 *
 * It used to be decided in four places that disagreed with each other. Two axes had been collapsed into
 * one flag, and they are not the same question:
 *
 * - **[savingNow] — quality.** Applies the moment the switch is on, on *any* connection. Home Wi-Fi that
 *   bills by the gigabyte is still worth saving on, and the user asked for the switch to mean what it
 *   says rather than quietly doing nothing until they leave the house.
 * - **[blocksBulkTransfer] — large, deliberate transfers.** Additionally requires the connection to cost
 *   money. Blocking a download on Wi-Fi would be backwards: downloading on Wi-Fi is *how* you avoid
 *   spending mobile data later.
 *
 * **Metered, never "is cellular".** That distinction is the bug this class exists to close: a phone
 * hotspot reports Wi-Fi transport while billing somebody's data plan, so `isCellular` is false and every
 * saving rule keyed on it silently switched itself off — including "only look for Lossless on Wi-Fi",
 * which would happily pull a 24 MB FLAC over a tethered connection. `CanvasPolicy` already got this
 * right, with a comment explaining why; nothing else did.
 */
@Singleton
class DataSaverState @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
) {

    /**
     * Whether to economise, from either switch.
     *
     * Only the app's own preference is observable — Android's has no change stream — so this re-reads the
     * system status on every emission of ours. In practice that is enough: the system toggle is set in
     * Settings, which means leaving the app, which means the flows resubscribe on the way back.
     */
    val saving: Flow<Boolean> = settings.dataSaver
        .map { appSwitch -> appSwitch || systemDataSaverOn() }
        .distinctUntilChanged()

    /**
     * Synchronous read, for the places that cannot suspend: the audio sink built once at service start,
     * and the ExoPlayer loader thread inside `resolveDataSpec`.
     *
     * `runBlocking` on a DataStore flow is the same cost the playback service already pays for the audio
     * quality mode at startup — a single already-cached read, not a network call.
     */
    fun savingNow(): Boolean =
        runCatching { runBlocking { saving.first() } }.getOrDefault(false)

    /**
     * Whether a large, deliberate transfer (a download) should wait.
     *
     * Both conditions, deliberately: economising *and* a connection that costs money. On Wi-Fi a download
     * is the opposite of waste.
     */
    fun blocksBulkTransfer(): Boolean = savingNow() && !network.snapshot().isUnmetered

    /**
     * The audio quality actually in force, which is not always the one that was chosen.
     *
     * The stored preference is **never overwritten** — writing `STANDARD` into it would leave the user
     * there for good once they turned the switch back off, having silently lost the choice they made.
     * So the override lives here, at the two reads that decide, and the Settings screen shows what is in
     * force and why.
     */
    suspend fun effectiveQualityMode(): AudioQualityMode =
        if (saving.first()) AudioQualityMode.STANDARD else settings.audioQualityMode.first()

    /**
     * Android's own Data Saver, when it applies to this app.
     *
     * `RESTRICT_BACKGROUND_STATUS_ENABLED` means the user turned the system switch on **and** did not
     * exempt Rizx; `WHITELISTED` means they did exempt it, which is them saying this app may spend. No
     * new permission — `ACCESS_NETWORK_STATE` is already granted — and guarded like every other read in
     * this package, because a system service that is unavailable must never look like "save nothing".
     */
    private fun systemDataSaverOn(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)
}
