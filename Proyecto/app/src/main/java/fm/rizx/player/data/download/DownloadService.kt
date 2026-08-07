package fm.rizx.player.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import fm.rizx.player.MainActivity
import fm.rizx.player.R
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.SpatialRenderState
import fm.rizx.player.domain.model.SpatialRenderStatus
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.SpatialRenderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while downloads run, so a 40-track batch survives the user leaving the app.
 *
 * Publishes **one aggregate notification** ("Downloading — 12 of 40 · 34%") rather than one per track:
 * the queue is sequential, so per-track notifications would only flicker. Sampled at 1 Hz — the progress
 * callback fires on every percent of every file.
 *
 * Not WorkManager: that would add two dependencies plus Hilt worker wiring and **still need this exact
 * permission** for `setForeground()` on API 34+, so it buys no permission relief. It also can't hold our
 * download URLs, which are ephemeral tokens that must never be persisted — WorkManager would write them
 * into its own database. The cost is that a process death mid-batch loses the in-flight track; the
 * completed ones are already indexed and re-tapping "Download all" fetches only what's missing.
 */
@AndroidEntryPoint
class DownloadService : android.app.Service() {

    @Inject lateinit var downloads: DownloadRepository

    @Inject lateinit var renders: SpatialRenderRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Post a notification immediately: Android requires startForeground() within a few seconds of
        // startForegroundService(), well before the first progress tick arrives.
        startForegroundSafely(buildNotification(done = 0, total = 0, percent = 0, rendering = false))
        watcher = scope.launch {
            // **Both queues, and this is the only place that sees both.** Stopping when downloads drain
            // would kill an 8D render mid-encode, which is the longest-running thing the app does.
            combine(downloads.states, renders.states) { d, r -> d to r }
                .sample(SAMPLE_MS)
                .collect { (states, renderStates) ->
                    // CONVERTING counts. It did not, and that was a real hole: an MP3 download's decode
                    // and re-encode is the slow half, and the service used to shut down the moment the
                    // bytes finished arriving — exactly when the process most needed keeping alive.
                    val active = states.values.filter {
                        it.status == DownloadStatus.QUEUED ||
                            it.status == DownloadStatus.DOWNLOADING ||
                            it.status == DownloadStatus.CONVERTING
                    }
                    val activeRenders = renderStates.values.filter {
                        it.status == SpatialRenderStatus.FETCHING || it.status == SpatialRenderStatus.RENDERING
                    }
                    if (active.isEmpty() && activeRenders.isEmpty()) {
                        stopSelf()
                        return@collect
                    }
                    val current = active.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                        ?: activeRenders.firstOrNull()
                    notificationManager()?.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            done = states.values.count { it.status == DownloadStatus.COMPLETE },
                            total = active.size + activeRenders.size,
                            percent = when (current) {
                                is DownloadState -> current.progressPercent
                                is SpatialRenderState -> current.progressPercent
                                else -> 0
                            },
                            // A render has no percentage worth showing once the encoder starts, so the
                            // bar goes indeterminate rather than sitting at whatever the fetch reached.
                            rendering = activeRenders.any { it.status == SpatialRenderStatus.RENDERING },
                        ),
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A rejected foreground start (Android 14 restricts starting one from the background) must never
     * crash the app — the downloads simply run as ordinary background work and may be killed sooner.
     *
     * Through `ServiceCompat` because the 3-arg `startForeground(id, notification, type)` only exists
     * on API 29+ — calling it directly on 26–28 would `NoSuchMethodError`, and this `runCatching`
     * would silently swallow exactly the failure it exists to survive.
     */
    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
    }

    private fun buildNotification(done: Int, total: Int, percent: Int, rendering: Boolean): Notification {
        val text = when {
            rendering -> "Rendering in 8D"
            total == 0 -> "Preparing…"
            total == 1 -> "1 song"
            else -> "$done of ${done + total} songs"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setProgress(100, percent, total == 0 || rendering)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Progress while songs are saved for offline listening" }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        private const val CHANNEL_ID = "rizx_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val SAMPLE_MS = 1_000L
    }
}

/**
 * Starts [DownloadService] when there is work to keep alive. It stops itself once every queue it
 * watches has drained — see [DownloadNotifier] for why stopping is not offered here.
 *
 * Guarded: a foreground start that Android refuses must leave the work running, not take the app down.
 */
class ServiceDownloadNotifier(private val context: Context) : DownloadNotifier {

    override fun start() {
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
    }
}
