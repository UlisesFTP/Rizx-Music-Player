package fm.rizx.player.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dagger.hilt.android.EntryPointAccessors
import fm.rizx.player.playback.service.PlaybackActions
import fm.rizx.player.playback.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A widget button, delivered to the playback session the way the app's own UI talks to it: through a
 * [MediaController]. Built for the tap and released after it, so the widget never keeps the service
 * bound on its own. When the service was not running, connecting starts it; the queue it restores from
 * disk arrives a moment later, so the command waits — briefly — for the player to have something to act on.
 */
@androidx.annotation.OptIn(UnstableApi::class) // PlaybackService itself is marked unstable, as Media3 services are
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        // The application context: a receiver's own context is not allowed to bind to services.
        val app = context.applicationContext
        scope.launch {
            try {
                handle(app, action, intent)
            } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: Context, action: String, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(app, WidgetEntryPoint::class.java)
        // The recognized song is looked up before the session is touched: nothing to play, nothing to start.
        val recognized = if (action == WidgetActions.PLAY_RECOGNIZED) {
            deps.recognition().history().first().firstOrNull()?.resolvedTrack ?: return
        } else null

        val controller = connect(app) ?: return
        try {
            awaitItems(controller)
            when (action) {
                WidgetActions.PLAY_PAUSE -> when {
                    controller.playbackState == Player.STATE_ENDED -> {
                        controller.seekTo(0)
                        controller.play()
                    }
                    controller.isPlaying || controller.playWhenReady -> controller.pause()
                    else -> {
                        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                        controller.play()
                    }
                }
                WidgetActions.NEXT -> controller.seekToNext()
                WidgetActions.PREVIOUS -> controller.seekToPrevious()
                WidgetActions.LIKE -> controller.sendCustomCommand(SessionCommand(PlaybackActions.TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
                WidgetActions.SEEK -> {
                    val fraction = intent.getFloatExtra(WidgetActions.EXTRA_FRACTION, -1f)
                    val duration = controller.duration
                    if (fraction in 0f..1f && duration > 0L) controller.seekTo((fraction * duration).toLong())
                }
                WidgetActions.PLAY_RECOGNIZED -> {
                    // The app's own path (song-seeded radio behind it), then a play through this controller
                    // in case the app's controller is not connected yet — a cold process, typically.
                    deps.playback().playAutoRadio(recognized!!)
                    delay(QUEUE_SETTLE_MS)
                    controller.play()
                }
            }
            // Let the command leave before the connection goes.
            delay(RELEASE_DELAY_MS)
        } finally {
            runCatching { controller.release() }
        }
    }

    private suspend fun connect(app: Context): MediaController? = withTimeoutOrNull(GIVE_UP_MS) {
        suspendCancellableCoroutine { continuation ->
            val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            future.addListener({ continuation.resume(runCatching { future.get() }.getOrNull()) }, ContextCompat.getMainExecutor(app))
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }

    /** A service that just started restores its queue from disk in a moment; give it that moment. */
    private suspend fun awaitItems(controller: MediaController) {
        repeat(READY_ATTEMPTS) {
            if (controller.mediaItemCount > 0) return
            delay(READY_POLL_MS)
        }
    }

    private companion object {
        const val TAG = "Widget"
        const val GIVE_UP_MS = 8_000L
        const val READY_POLL_MS = 100L
        const val READY_ATTEMPTS = 25
        const val QUEUE_SETTLE_MS = 300L
        const val RELEASE_DELAY_MS = 400L

        /** Main, immediately: a MediaController must be driven from its application looper. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
