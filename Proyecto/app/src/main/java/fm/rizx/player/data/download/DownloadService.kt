package fm.rizx.player.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import fm.rizx.player.MainActivity
import fm.rizx.player.R
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Post a notification immediately: Android requires startForeground() within a few seconds of
        // startForegroundService(), well before the first progress tick arrives.
        startForegroundSafely(buildNotification(done = 0, total = 0, percent = 0))
        watcher = scope.launch {
            downloads.states.sample(SAMPLE_MS).collect { states ->
                val active = states.values.filter {
                    it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
                }
                if (active.isEmpty()) {
                    stopSelf()
                    return@collect
                }
                val current = active.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                notificationManager()?.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        done = states.values.count { it.status == DownloadStatus.COMPLETE },
                        total = active.size,
                        percent = current?.progressPercent ?: 0,
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
     */
    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    private fun buildNotification(done: Int, total: Int, percent: Int): Notification {
        val text = when {
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
            .setProgress(100, percent, total == 0)
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
 * Starts and stops [DownloadService] as the queue fills and drains.
 *
 * Every call is guarded: a foreground start that Android refuses must leave the download running, not
 * take the app down with it.
 */
class ServiceDownloadNotifier(private val context: Context) : DownloadNotifier {

    override fun start() {
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
    }

    override fun stop() {
        runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
    }
}
