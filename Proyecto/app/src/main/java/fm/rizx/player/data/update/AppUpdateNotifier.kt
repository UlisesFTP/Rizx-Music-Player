package fm.rizx.player.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fm.rizx.player.MainActivity
import fm.rizx.player.R
import fm.rizx.player.domain.update.AppUpdate

/**
 * The "a new version is out" notification. Tapping it — or its one action — opens the app on the
 * update dialog (`MainActivity` turns [MainActivity.ACTION_OPEN_UPDATE] into an inbox request).
 * Default importance: it makes a sound once, like a message, and never repeats for the same version
 * (the coordinator hands out one claim per version). Posting is best-effort: without the runtime
 * permission on API 33+ nothing is shown and nothing crashes.
 */
class AppUpdateNotifier(private val context: Context) {

    fun notify(update: AppUpdate) {
        if (!canPost()) return
        createChannel()
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val firstLine = update.notes.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotBlank() }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_notification_title, update.versionName))
            .setContentText(firstLine ?: context.getString(R.string.update_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(firstLine?.let { "$it\n\n" }.orEmpty() + context.getString(R.string.update_notification_text)))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.update_notification_action), open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /** Takes the notification down once the user has acted on it from inside the app. */
    fun dismiss() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.update_notification_channel_caption) }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "rizx_updates"
        const val NOTIFICATION_ID = 4301
        const val REQUEST_OPEN = 4302
    }
}
