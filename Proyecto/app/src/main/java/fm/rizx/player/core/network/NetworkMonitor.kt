package fm.rizx.player.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Downstream estimates below this (kbps, but known) count as a weak signal → prefer a lower bitrate. */
private const val BAD_SIGNAL_KBPS = 3_000

/**
 * A cheap, on-demand read of the active network's transport + estimated downstream bandwidth, used to
 * adapt audio quality (data saver on cellular / bad signal). Requires only `ACCESS_NETWORK_STATE`
 * (already granted). No callbacks/registration: we sample right when a stream is about to resolve —
 * exactly when the decision matters. Every call is guarded — on any failure it reports an "unknown,
 * unrestricted" snapshot so playback quality is never wrongly throttled.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** A point-in-time read of the active network. */
    data class Snapshot(val isCellular: Boolean, val downstreamKbps: Int) {
        /** A low (but known) downstream-bandwidth estimate — treat as a weak signal. 0/unknown ⇒ false. */
        val isBadSignal: Boolean get() = downstreamKbps in 1 until BAD_SIGNAL_KBPS
    }

    fun snapshot(): Snapshot = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching UNKNOWN
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return@runCatching UNKNOWN
        Snapshot(
            isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            downstreamKbps = caps.linkDownstreamBandwidthKbps,
        )
    }.getOrDefault(UNKNOWN)

    private companion object {
        /** No/unknown network: not cellular, unknown bandwidth (0 ⇒ never flagged as bad signal). */
        val UNKNOWN = Snapshot(isCellular = false, downstreamKbps = 0)
    }
}
