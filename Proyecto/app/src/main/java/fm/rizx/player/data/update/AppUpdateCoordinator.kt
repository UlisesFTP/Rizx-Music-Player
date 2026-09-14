package fm.rizx.player.data.update

import fm.rizx.player.domain.update.AppUpdate
import fm.rizx.player.domain.update.AppUpdateFailure
import fm.rizx.player.domain.update.AppUpdateRepository
import fm.rizx.player.domain.update.AppUpdateState
import fm.rizx.player.domain.update.SemanticVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The one place that knows where the update flow stands (spec 024). Everything else — the periodic
 * worker, the Settings row, the dialog, the notification — reads [state] and asks for a step.
 *
 * A check asks GitHub at most once every [CHECK_INTERVAL_MS] unless forced; inside that window it
 * answers from what the store remembers, so a phone that was told about a release overnight shows
 * it in Settings the next morning without a second request. A version the user skipped counts as
 * "up to date" until a newer one appears.
 */
class AppUpdateCoordinator(
    private val repository: AppUpdateRepository,
    private val store: AppUpdateStore,
    private val downloader: ApkDownloader,
    private val installedVersion: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Unknown)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val checking = Mutex()
    private var downloadJob: Job? = null

    /**
     * Looks for a newer build and publishes the result. Returns the update when one is newer than the
     * installed version and not skipped; null otherwise (including after a failed lookup, which is
     * published as [AppUpdateState.Failed]).
     */
    suspend fun check(force: Boolean = false): AppUpdate? = checking.withLock {
        // A download in flight or done is the answer already; a check must not reset it.
        when (val current = _state.value) {
            is AppUpdateState.Downloading -> return current.update
            is AppUpdateState.Ready -> return current.update
            else -> Unit
        }
        val now = clock()
        val stale = now - store.lastCheckedAtMs() >= CHECK_INTERVAL_MS
        val update: AppUpdate? = if (force || stale) {
            _state.value = AppUpdateState.Checking
            try {
                repository.latest().also { store.saveCheck(it, now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AppUpdateState.Failed(null, e.toFailure())
                return null
            }
        } else {
            store.lastKnown()
        }
        publish(update)
    }

    private suspend fun publish(update: AppUpdate?): AppUpdate? {
        val skipped = store.skippedVersion()
        val newer = update != null && SemanticVersion.isNewer(update.versionName, installedVersion)
        if (update == null || !newer) {
            _state.value = AppUpdateState.UpToDate(installedVersion)
            return null
        }
        if (update.versionName == skipped) {
            _state.value = AppUpdateState.UpToDate(installedVersion, skippedVersion = skipped)
            return null
        }
        val file = downloader.existing(update)
        _state.value = if (file != null) AppUpdateState.Ready(update, file.absolutePath) else AppUpdateState.Available(update)
        return update
    }

    /** Fetches the available update's APK. A second call while one runs is ignored. */
    fun download() {
        val update = _state.value.current ?: return
        if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.Ready) return
        _state.value = AppUpdateState.Downloading(update, 0L, update.apkBytes)
        downloadJob = scope.launch {
            try {
                val file = downloader.download(update) { done, total ->
                    _state.value = AppUpdateState.Downloading(update, done, total)
                }
                _state.value = AppUpdateState.Ready(update, file.absolutePath)
            } catch (e: CancellationException) {
                _state.value = AppUpdateState.Available(update)
                throw e
            } catch (e: Exception) {
                _state.value = AppUpdateState.Failed(update, e.toFailure())
            }
        }
    }

    /** Stops a download in flight; the update stays available. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /** The user does not want this version: it stops being offered until a newer one appears. */
    suspend fun skip() {
        val update = _state.value.current ?: return
        cancelDownload()
        store.setSkippedVersion(update.versionName)
        _state.value = AppUpdateState.UpToDate(installedVersion, skippedVersion = update.versionName)
    }

    /** After a failure: back to the step before it, so the dialog's "retry" has a next move. */
    suspend fun retry() {
        val failed = _state.value as? AppUpdateState.Failed ?: return
        val update = failed.update
        if (update == null) check(force = true) else _state.value = AppUpdateState.Available(update)
    }

    /**
     * True exactly once per version: the caller may post the "new version" notification. Later checks
     * that find the same version get false, so a release is announced once, not daily.
     */
    suspend fun claimNotification(update: AppUpdate): Boolean {
        if (store.notifiedVersion() == update.versionName) return false
        store.setNotifiedVersion(update.versionName)
        return true
    }

    private fun Throwable.toFailure(): AppUpdateFailure = when (this) {
        is ApkVerificationException -> AppUpdateFailure.VERIFICATION
        is IOException -> AppUpdateFailure.NETWORK
        is retrofit2.HttpException -> AppUpdateFailure.NETWORK
        else -> AppUpdateFailure.UNKNOWN
    }

    companion object {
        /** How long a lookup's answer is trusted before the next check asks GitHub again. */
        const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}
