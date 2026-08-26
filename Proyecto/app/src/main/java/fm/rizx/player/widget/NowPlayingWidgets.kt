package fm.rizx.player.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.recognition.RecognitionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The 4×2 card: cover, title, artist, clock, the red dotted seek bar, transport, like and the microphone. */
class NowPlayingCardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshWidgets(context)
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = refreshWidgets(context)
}

/** The 4×1 row: cover, title, artist, transport and like. */
class NowPlayingBarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshWidgets(context)
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = refreshWidgets(context)
}

/** The 2×2 Audio ID card: the microphone, and the last song it identified with a play button. */
class AudioIdWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshWidgets(context)
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = refreshWidgets(context)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun updater(): NowPlayingWidgetUpdater
    fun recognition(): RecognitionRepository
    fun playback(): PlaybackController
}

private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Every provider does the same thing on every callback: ask the updater to paint. Kept asynchronous
 * behind [AppWidgetProvider.goAsync] so the disk read and the cover fetch finish before the receiver's
 * process is allowed to go idle — and never crash the launcher's broadcast.
 */
private fun AppWidgetProvider.refreshWidgets(context: Context) {
    val pending = goAsync()
    providerScope.launch {
        try {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).updater().refresh()
        } catch (e: Exception) {
            android.util.Log.w("Widget", "refresh failed", e)
        } finally {
            pending.finish()
        }
    }
}
