package fm.rizx.player.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.BuildConfig
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.data.update.AppUpdateCoordinator
import fm.rizx.player.data.update.AppUpdateInstaller
import fm.rizx.player.data.update.AppUpdateNotifier
import fm.rizx.player.domain.update.AppUpdateInbox
import fm.rizx.player.domain.update.AppUpdateState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The update flow as the UI sees it: one [state] for the Settings row and the dialog, the actions the
 * dialog offers, and the inbox that a notification tap lands in. Everything stateful lives in the
 * singleton coordinator, so the row in Settings and the dialog opened from a notification agree.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val coordinator: AppUpdateCoordinator,
    private val inbox: AppUpdateInbox,
    private val installer: AppUpdateInstaller,
    private val notifier: AppUpdateNotifier,
    private val network: NetworkMonitor,
) : ViewModel() {

    val state: StateFlow<AppUpdateState> = coordinator.state

    /** Bumps when a notification asks for the dialog; the Settings screen consumes it. */
    val pendingOpen: StateFlow<Long> = inbox.pending

    val installedVersion: String = BuildConfig.VERSION_NAME

    fun consumeOpen(): Boolean = inbox.consume()

    /** Ask GitHub (when forced or when the last answer is old) and publish what it says. */
    fun check(force: Boolean = false) {
        viewModelScope.launch { coordinator.check(force) }
    }

    fun download() {
        notifier.dismiss()
        coordinator.download()
    }

    fun cancelDownload() = coordinator.cancelDownload()

    fun skip() {
        notifier.dismiss()
        viewModelScope.launch { coordinator.skip() }
    }

    fun retry() {
        viewModelScope.launch { coordinator.retry() }
    }

    /** True when the download would ride on mobile data — the dialog says so next to the size. */
    fun onMobileData(): Boolean = network.snapshot().isCellular

    fun canInstall(): Boolean = installer.canInstall()

    fun permissionIntent(): Intent = installer.permissionIntent()

    fun installIntent(filePath: String): Intent = installer.installIntent(File(filePath))
}
