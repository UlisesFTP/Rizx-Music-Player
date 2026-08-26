package fm.rizx.player.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.data.local.store.PlaybackSessionStore
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.domain.recognition.RecognitionRepository
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps every placed widget in step with the app.
 *
 * Now Playing has two sources, one door: while the playback service is alive it [push]es its own view of
 * the moment (play/pause, a new song, a like, a tick every few seconds); when a provider asks for a
 * refresh with no service running — the phone rebooted, the launcher restarted, a widget was just added
 * — the last session on disk supplies the song, paused, at the second it stopped. Renders are conflated:
 * a burst of pushes paints once, with the latest.
 *
 * Audio ID follows the recognition session and its history directly: the phase while the app listens,
 * the last identified song otherwise.
 */
@Singleton
class NowPlayingWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: PlaybackSessionStore,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
    private val artworkEnricher: TrackArtworkEnricher,
    private val recognition: RecognitionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renderer = WidgetRenderer(context)
    private val requests = MutableSharedFlow<WidgetSnapshot>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What the service last said; null until it speaks, so a cold provider reads the disk instead. */
    @Volatile
    private var live: WidgetSnapshot? = null

    private val covers = CoverCache()
    private val audioCovers = CoverCache()

    /** The song a cover was last borrowed for (misses included), so a tick never repeats the lookup. */
    private var borrowedFor: String? = null
    private var borrowedUrl: String? = null

    init {
        scope.launch { requests.collectLatest { render(it) } }
        // A theme picked in Settings repaints the cards; the device's own dark-mode flips are caught by
        // the next playback event, which is never far away while a widget is on screen.
        scope.launch { settings.themeMode.drop(1).collect { runCatching { refresh() } } }
        // Recognition: the phase while listening, the newest history entry otherwise.
        scope.launch {
            combine(recognition.state, recognition.history()) { state, history -> AudioIdSnapshot.of(state, history.firstOrNull()) }
                .distinctUntilChanged()
                .collectLatest { runCatching { renderAudioId(it) } }
        }
    }

    /** The playback service's view of the moment. Costs one binder call when no widget is placed. */
    fun push(snapshot: WidgetSnapshot) {
        live = snapshot
        if (placed()) requests.tryEmit(snapshot)
    }

    /** A provider callback: paint what is known, live or from disk. Suspends until the widgets are updated. */
    suspend fun refresh() {
        render(live ?: fromDisk())
        renderAudioId(AudioIdSnapshot.of(recognition.state.value, recognition.history().first().firstOrNull()))
    }

    private fun manager(): AppWidgetManager = AppWidgetManager.getInstance(context)

    private fun ids(provider: Class<*>): IntArray = runCatching { manager().getAppWidgetIds(ComponentName(context, provider)) }.getOrDefault(IntArray(0))

    private fun placed(): Boolean = ids(NowPlayingCardWidget::class.java).isNotEmpty() || ids(NowPlayingBarWidget::class.java).isNotEmpty()

    private suspend fun fromDisk(): WidgetSnapshot {
        val saved = runCatching { sessionStore.load() }.getOrNull() ?: return WidgetSnapshot.EMPTY
        val track = saved.items.getOrNull(saved.currentIndex)?.track ?: return WidgetSnapshot.EMPTY
        val liked = runCatching { favorites.isFavoriteTrack(track.source).first() }.getOrDefault(false)
        return WidgetSnapshot.of(track, isPlaying = false, liked = liked, positionMs = saved.positionMs, durationMs = null)
    }

    private suspend fun palette(): WidgetPalette {
        val mode = runCatching { settings.themeMode.first() }.getOrDefault(ThemeMode.SYSTEM)
        return WidgetPalette.resolve(mode, systemDark())
    }

    private suspend fun render(snapshot: WidgetSnapshot) {
        val cards = ids(NowPlayingCardWidget::class.java)
        val bars = ids(NowPlayingBarWidget::class.java)
        if (cards.isEmpty() && bars.isEmpty()) return
        val palette = palette()
        val cover = covers.get(snapshot.artworkUrl ?: borrowedCover(snapshot))
        val manager = manager()
        for (id in cards) update(manager, id, WidgetRenderer.Kind.CARD, snapshot, palette, cover)
        for (id in bars) update(manager, id, WidgetRenderer.Kind.BAR, snapshot, palette, cover)
    }

    private suspend fun renderAudioId(snapshot: AudioIdSnapshot) {
        val ids = ids(AudioIdWidget::class.java)
        if (ids.isEmpty()) return
        val palette = palette()
        val cover = audioCovers.get(snapshot.artworkUrl)
        val manager = manager()
        for (id in ids) {
            val widthDp = options(manager, id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
            runCatching { manager.updateAppWidget(id, renderer.renderAudioId(snapshot, palette, cover, widthDp)) }
        }
    }

    private fun update(manager: AppWidgetManager, id: Int, kind: WidgetRenderer.Kind, snapshot: WidgetSnapshot, palette: WidgetPalette, cover: Bitmap?) {
        val options = options(manager, id)
        // Portrait: the launcher reports the width as the minimum and the height as the maximum.
        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        runCatching { manager.updateAppWidget(id, renderer.render(kind, snapshot, palette, cover, widthDp, heightDp)) }
    }

    private fun options(manager: AppWidgetManager, id: Int) = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()

    /**
     * A cover for a song that has none of its own — imported Spotify favorites, typically — found the
     * way the app's lists find theirs. One lookup per song, hit or miss.
     */
    private suspend fun borrowedCover(snapshot: WidgetSnapshot): String? {
        val track = snapshot.track ?: return null
        val key = snapshot.identityKey ?: return null
        if (key == borrowedFor) return borrowedUrl
        val url = runCatching { artworkEnricher.coverFor(track) }.getOrNull()
        borrowedFor = key
        borrowedUrl = url
        return url
    }

    /** One cover at a time as a small software bitmap (RemoteViews cannot carry hardware ones). */
    private inner class CoverCache {
        private var url: String? = null
        private var bitmap: Bitmap? = null

        suspend fun get(wanted: String?): Bitmap? {
            if (wanted == null) return null
            if (wanted == url) return bitmap
            val px = (ART_DP * context.resources.displayMetrics.density).toInt()
            val request = ImageRequest.Builder(context).data(wanted).size(px).allowHardware(false).build()
            val result = try {
                context.imageLoader.execute(request)
            } catch (e: CancellationException) {
                throw e // a newer snapshot superseded this render; nothing to log
            } catch (e: Exception) {
                Log.w(TAG, "cover load threw for $wanted", e)
                null
            }
            if (result is ErrorResult) Log.w(TAG, "cover load failed for $wanted", result.throwable)
            val drawable = result?.drawable
            bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable?.let { runCatching { it.toBitmap(px, px) }.getOrNull() }
            url = wanted
            return bitmap
        }
    }

    private fun systemDark(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val TAG = "Widget"
        const val DEFAULT_WIDTH_DP = 250
        const val DEFAULT_HEIGHT_DP = 110
        const val ART_DP = 64
    }
}
