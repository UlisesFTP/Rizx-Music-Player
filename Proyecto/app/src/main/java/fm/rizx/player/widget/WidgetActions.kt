package fm.rizx.player.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import fm.rizx.player.MainActivity

/** The taps a widget can make, as PendingIntents the launcher fires on the app's behalf. */
object WidgetActions {
    const val PLAY_PAUSE = "fm.rizx.player.widget.PLAY_PAUSE"
    const val NEXT = "fm.rizx.player.widget.NEXT"
    const val PREVIOUS = "fm.rizx.player.widget.PREVIOUS"
    const val LIKE = "fm.rizx.player.widget.LIKE"

    /** A tap on the progress bar; [EXTRA_FRACTION] says where along the song. */
    const val SEEK = "fm.rizx.player.widget.SEEK"
    const val EXTRA_FRACTION = "fraction"

    /** Play the song the last recognition resolved. */
    const val PLAY_RECOGNIZED = "fm.rizx.player.widget.PLAY_RECOGNIZED"

    /** The dotted bar is covered by this many equal tap zones: a tap lands within ~4 % of the song. */
    const val SEEK_SEGMENTS = 24

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** A transport, like or play tap: handled by [WidgetActionReceiver] through the media session. */
    fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, WidgetActionReceiver::class.java).setAction(action),
        FLAGS,
    )

    /** One tap zone of the seek bar; each zone is its own PendingIntent, so each carries its own fraction. */
    fun seek(context: Context, index: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        SEEK.hashCode() + index,
        Intent(context, WidgetActionReceiver::class.java)
            .setAction(SEEK)
            .putExtra(EXTRA_FRACTION, DotBar.tapFraction(index, SEEK_SEGMENTS)),
        FLAGS,
    )

    /** The cover, the title, the card itself: bring the app up. */
    fun open(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, REQUEST_OPEN, it, FLAGS)
        }

    /** The microphone: the app lands on the recognition screen and starts listening. */
    fun recognize(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_RECOGNIZE,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RECOGNIZE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        FLAGS,
    )

    private const val REQUEST_OPEN = 1001
    private const val REQUEST_RECOGNIZE = 1002
}
