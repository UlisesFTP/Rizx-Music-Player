package fm.rizx.player

import android.content.Context
import android.net.ConnectivityManager
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.core.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk

/**
 * A real [DataSaverState] over fake sensors.
 *
 * The class itself is worth exercising rather than faking: folding two switches and a metered flag into
 * "save" and "hold this transfer" is exactly the logic that used to be spread across four files and
 * disagree with itself.
 *
 * [unmetered] defaults to true because that is the connection the app spends most of its life on, and it
 * is the one where "saving is on but a download should still run" has to hold.
 */
fun dataSaverState(
    settings: FakeSettingsRepository = FakeSettingsRepository(),
    unmetered: Boolean = true,
    systemSaver: Boolean = false,
): DataSaverState = DataSaverState(
    context = fakeContext(systemSaver),
    settings = settings,
    network = mockk {
        every { snapshot() } returns NetworkMonitor.Snapshot(
            // Cellular follows metered here only for readability; nothing under test reads it any more,
            // which is the point — a hotspot is metered Wi-Fi and used to slip through every check.
            isCellular = !unmetered,
            downstreamKbps = 50_000,
            isUnmetered = unmetered,
        )
    },
)

/** A Context whose ConnectivityManager reports Android's own Data Saver as [on]. */
private fun fakeContext(on: Boolean): Context {
    val cm = mockk<ConnectivityManager> {
        every { restrictBackgroundStatus } returns if (on) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } else {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        }
    }
    return mockk { every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm }
}
