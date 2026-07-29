package fm.rizx.player.playback.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.os.Bundle
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import fm.rizx.player.data.local.store.PlaybackSessionSnapshot
import fm.rizx.player.data.local.store.PlaybackSessionStore
import fm.rizx.player.domain.model.PlaybackQueue
import fm.rizx.player.domain.model.QueueItemStatus
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.R
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.playback.LogoBitmapLoader
import fm.rizx.player.playback.toTimelineMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The single owner of the real ExoPlayer, the [MediaSession], and the media notification (AGENTS.md
 * "final architecture"; NUCLEAR_UPSTREAM_STUDY.md §6.6). The player holds the **whole queue** as a
 * timeline of placeholder MediaItems so notification / lock-screen / headset next-prev work natively;
 * [QueueStreamResolver] resolves each placeholder to its real stream just-in-time. Media3's
 * `MediaSessionService` provides the foreground notification, media-button, and lock-screen handling.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var queue: QueueRepository
    @Inject lateinit var streamResolver: QueueStreamResolver
    @Inject lateinit var recentlyPlayed: RecentlyPlayedRepository
    @Inject lateinit var audioEffects: fm.rizx.player.playback.AudioEffects
    @Inject lateinit var visualizer: fm.rizx.player.playback.AudioVisualizer
    @Inject lateinit var sessionStore: PlaybackSessionStore
    @Inject lateinit var radioTracks: fm.rizx.player.domain.usecase.GetRadioTracksUseCase
    @Inject lateinit var youtubeMixTracks: fm.rizx.player.domain.usecase.GetYoutubeMixTracksUseCase

    /** Song-seeded "up next" engines by mode; a mode with no entry uses the artist radio. */
    @Inject lateinit var mixSources: Map<RadioMode, @JvmSuppressWildcards fm.rizx.player.domain.provider.RadioMixSource>
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var audioCache: fm.rizx.player.playback.cache.AudioCache
    @Inject lateinit var cacheCompleter: fm.rizx.player.playback.cache.CacheCompleter
    @Inject lateinit var favorites: FavoritesRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession

    /** Guards the queue→player sync so player-driven transitions don't echo back into a loop. */
    private var applyingQueue = false

    /** A session snapshot loaded at startup, consumed by the first queue→player sync to seed the saved
     *  index + position (paused). Non-null only during restore. */
    private var pendingRestore: PlaybackSessionSnapshot? = null

    /** Low-frequency ticker that snapshots the playback position while a track is actually playing. */
    private var saveTicker: Job? = null

    /** Re-entrancy guard so the radio auto-refill fires one fetch at a time. */
    private var radioFetching = false

    // ---- Transition fade (Crossfade + Gapless). A position-driven volume envelope, active only when
    // the user turns Crossfade on or Gapless off; otherwise fadeMs == 0 and playback is fully seamless. ----
    private var crossfadeOn = false
    private var gaplessOn = true
    private var fadeMs = 0L
    private var fadeTicker: Job? = null
    private var lastAppliedVolume = 1f
    /** Fade-IN applies only right after an automatic/repeat transition — never on a manual skip/seek. */
    private var fadeInArmed = false

    /** Queue-item ids already retried as HLS, so a genuinely-broken HLS stream can't loop. */
    private val hlsRetried = mutableSetOf<String>()

    /** Player buffering state, mirrored for off-main-thread readers (see [startCacheCompletion]). */
    @Volatile
    private var buffering = false

    /** Live favorite/repeat state of the current track, mirrored so the notification's custom buttons
     *  (heart fill + repeat glyph) can be rebuilt without re-querying on every refresh. */
    private var currentFavorite = false
    private var currentRepeat = RepeatMode.OFF

    override fun onCreate() {
        super.onCreate()

        // Streamed audio is cached to disk as it plays, so a replay costs no network and works offline.
        // The cache wraps *only* the HTTP source: `DefaultDataSource` routes file:// itself, which keeps
        // downloaded and on-device songs from being copied a second time into the cache.
        val cachingHttpFactory = CacheDataSource.Factory()
            .setCache(audioCache.cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
            // A corrupt or unreadable cache entry falls through to the network instead of failing the song.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, cachingHttpFactory),
            streamResolver,
        )
        // Hi-Res output (opt-in): force the 32-bit float PCM output path so high-resolution local files
        // aren't truncated to 16-bit. Read once here because the sink is built once — the setting therefore
        // applies to this playback session (a live toggle takes effect on the next service start). It's a
        // tiny startup read, like AudioEffects reads normalizeVolume; a no-op for 16-bit/lossy sources.
        val hiResOutput = runBlocking { settings.hiResOutput.first() }
        // A pass-through TeeAudioProcessor taps the decoded PCM so the Now Playing waveform can react to
        // the actual audio spectrum. It runs before the default silence/speed processors and outputs the
        // buffer unchanged, so it doesn't affect playback. No RECORD_AUDIO permission (it's our own audio).
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput || hiResOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(visualizer.sink)))
                .build()
        }
        // Tuned buffering for near-instant response: start audio after a short pre-roll (not the 2.5 s
        // default), and keep a back-buffer so seeking backward within it is instant (no re-download).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
            .build()
        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug (§9 noisy behavior)
            .build()
            .apply {
                addListener(playerListener)
                setSeekParameters(SeekParameters.CLOSEST_SYNC) // snap to nearest sync sample — faster seeks
            }

        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            // Stamp the Rizx logomark onto every cover the session hands the notification / lock screen /
            // output switcher. The cache wraps the watermarker so each cover is composited only once.
            .setBitmapLoader(CacheBitmapLoader(LogoBitmapLoader(this, DataSourceBitmapLoader(this))))
            // Reference layout: a favorite (heart) and a repeat toggle alongside the transport. The seekbar
            // and prev/play/next are the system's; these two are the extra content we add.
            .setCustomLayout(buildCustomLayout())
            .apply { sessionActivityIntent()?.let { setSessionActivity(it) } }
            .build()

        // Bind the equalizer to the player's audio session and restore persisted settings (Phase 15).
        audioEffects.attach(player.audioSessionId)

        startCacheCompletion()

        // Restore the last session (queue + cursor + position) before wiring the queue→player sync, so
        // the app resumes on the same song at the same second after the process was killed. Thereafter,
        // every queue change re-persists the snapshot (the position is captured live from the player).
        scope.launch {
            pendingRestore = runCatching { sessionStore.load() }.getOrNull()
            pendingRestore?.let {
                queue.restore(it.items, it.currentIndex, it.repeatMode, it.context, it.shuffleOn, it.unshuffledIds)
            }
            queue.state.collect { q ->
                syncFromQueue(q)
                if (pendingRestore == null) persist()
            }
        }
        // Endless radio: when a RADIO queue nears its end, append more similar tracks so next keeps going.
        // Also pre-resolve the neighbours of the current item so skip/next hits a warm cache (near-instant).
        scope.launch {
            queue.state.collect { q ->
                maybeRefillRadio(q)
                warmUpcoming(q)
            }
        }

        // Crossfade / Gapless: recompute the transition fade whenever either setting changes.
        scope.launch { settings.crossfade.collect { crossfadeOn = it; recomputeFade() } }
        scope.launch { settings.gapless.collect { gaplessOn = it; recomputeFade() } }

        // Hi-Res also decides which *codec* gets requested, and resolved URLs are reused for hours — so
        // without this, flipping the toggle would appear to do nothing until the cache aged out.
        scope.launch {
            settings.hiResOutput.distinctUntilChanged().drop(1).collect { streamResolver.clearUrlCache() }
        }

        // Keep the notification's heart + repeat buttons in sync with the current track's favorite state
        // and the queue's repeat mode, so the glyphs reflect reality (filled heart, repeat-one).
        scope.launch {
            queue.state
                .flatMapLatest { q ->
                    val track = q.current?.track
                    val favFlow = track?.let { favorites.isFavoriteTrack(it.source) } ?: flowOf(false)
                    favFlow.map { fav -> fav to q.repeatMode }
                }
                .distinctUntilChanged()
                .collect { (fav, repeat) ->
                    currentFavorite = fav
                    currentRepeat = repeat
                    if (::session.isInitialized) session.setCustomLayout(buildCustomLayout())
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistNow() // the app was swiped away — capture the exact spot before we may stop
        // If the user swipes the app away while paused (or nothing is loaded), stop the service.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        persistNow() // read the final position while the player is still alive
        stopSaveTicker()
        scope.cancel()
        streamResolver.release()
        audioEffects.release()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ---- Session persistence (resume the same song at the same second after a cold start) ----

    /** The current queue + live player position as a restorable snapshot. */
    private fun currentSnapshot(): PlaybackSessionSnapshot {
        val q = queue.state.value
        return PlaybackSessionSnapshot(
            items = q.items,
            currentIndex = q.currentIndex,
            repeatMode = q.repeatMode,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            context = q.context,
            shuffleOn = q.shuffleOn,
            unshuffledIds = q.unshuffledIds,
        )
    }

    /** Persists the session off the main thread (used on queue changes, pause, and the save ticker). */
    private fun persist() {
        if (pendingRestore != null) return // don't overwrite the saved spot before the restore applies
        val snapshot = currentSnapshot()
        scope.launch { runCatching { sessionStore.save(snapshot) } }
    }

    /** Persists synchronously — for teardown paths where a launched coroutine wouldn't survive. */
    private fun persistNow() {
        if (pendingRestore != null) return
        val snapshot = currentSnapshot()
        runCatching { runBlocking { sessionStore.save(snapshot) } }
    }

    /**
     * Tops up half-cached songs on an unmetered connection, so a track you skipped near the end stops
     * needing the network forever.
     *
     * Runs on the service's own scope: this process is already alive and already foreground while music
     * plays, which is exactly when the user is on a network they chose. The first pass waits a while so it
     * can't compete with the song that just started, and it pauses whenever the player is buffering —
     * finishing an old download must never make the current one stutter.
     */
    private fun startCacheCompletion() {
        scope.launch {
            delay(FIRST_COMPLETION_DELAY_MS)
            while (true) {
                // `buffering` rather than `player.playbackState`: this runs off the main thread, and
                // ExoPlayer throws when its state is read from the wrong one.
                runCatching { cacheCompleter.completePending(shouldWait = { buffering }) }
                delay(COMPLETION_INTERVAL_MS)
            }
        }
    }

    private fun startSaveTicker() {
        if (saveTicker?.isActive == true) return
        saveTicker = scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                persist()
            }
        }
    }

    private fun stopSaveTicker() {
        saveTicker?.cancel()
        saveTicker = null
    }

    // ---- Endless radio auto-refill (Spotify-style: keep appending similar tracks near the end) ----

    private fun maybeRefillRadio(q: PlaybackQueue) {
        if (q.context.kind != QueueSourceKind.RADIO || q.items.isEmpty()) return
        if (q.currentIndex < q.items.lastIndex - RADIO_REFILL_AHEAD) return // not near the end yet
        if (q.items.size >= RADIO_MAX_QUEUE) return // Repeat ALL re-enters the tail forever — don't grow unbounded
        if (radioFetching) return
        val seed = q.current?.track ?: return
        radioFetching = true
        scope.launch {
            try {
                // Seed from the current track and exclude everything already queued so nothing repeats.
                val exclude = q.items.map { it.track.source }.toSet()
                // The artist radio is both an engine and everyone else's floor: a song-seeded engine
                // that comes back empty (or has no implementation registered) falls back to it, so
                // "next" never dies whichever the user picked.
                val engine = mixSources[q.context.radioMode]
                val more = engine
                    ?.let { youtubeMixTracks(seed, exclude, source = it).ifEmpty { radioTracks(seed, exclude) } }
                    ?: radioTracks(seed, exclude)
                if (more.isNotEmpty()) queue.addToQueue(more)
            } catch (_: Exception) {
                // A failed radio fetch just means no new tracks this round — never break playback.
            } finally {
                radioFetching = false
            }
        }
    }

    /** Pre-resolve the current item's neighbours (prev + next two) so a skip / ExoPlayer's own next-item
     *  pre-buffer hits a warm stream cache and starts almost instantly. Cheap + guarded inside [warm]. */
    private fun warmUpcoming(q: PlaybackQueue) {
        if (q.items.isEmpty()) return
        val cur = q.currentIndex
        val neighbours = intArrayOf(cur - 1, cur + 1, cur + 2)
            .filter { it in q.items.indices && it != cur }
            .map { q.items[it] }
        if (neighbours.isNotEmpty()) streamResolver.warm(neighbours)
    }

    // ---- Transition fade (Crossfade + Gapless) ----

    /**
     * Derive the fade length from the two settings and start/stop the envelope ticker:
     * Crossfade on → a longer fade; else Gapless off → a short fade (a perceptible gap); else 0 (seamless,
     * ExoPlayer's automatic gapless — the default, zero overhead). Crossfade wins when both are set.
     */
    private fun recomputeFade() {
        val newFade = when {
            crossfadeOn -> CROSSFADE_MS
            !gaplessOn -> GAP_FADE_MS
            else -> 0L
        }
        if (newFade == fadeMs) return
        fadeMs = newFade
        if (fadeMs <= 0L) {
            stopFadeTicker()
            setPlayerVolume(1f) // back to seamless, full volume
        } else {
            startFadeTicker()
        }
    }

    private fun startFadeTicker() {
        if (fadeTicker?.isActive == true) return
        fadeTicker = scope.launch {
            while (true) {
                applyFadeEnvelope()
                delay(FADE_TICK_MS)
            }
        }
    }

    private fun stopFadeTicker() {
        fadeTicker?.cancel()
        fadeTicker = null
    }

    /**
     * A stateless volume envelope evaluated from the live position: fade **in** over the first [fadeMs]
     * of a track (only when armed by an automatic transition) and fade **out** over its last [fadeMs].
     * Being position-driven, it self-corrects on seeks and never gets "stuck" at a low volume.
     */
    private fun applyFadeEnvelope() {
        val f = fadeMs
        if (f <= 0L) { setPlayerVolume(1f); return }
        if (player.mediaItemCount == 0) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration // C.TIME_UNSET (negative) when the duration isn't known yet
        val fadeIn = if (fadeInArmed && pos < f) pos.toFloat() / f else 1f
        val fadeOut = if (dur > 0L && dur - pos < f) (dur - pos).toFloat() / f else 1f
        setPlayerVolume(minOf(fadeIn, fadeOut).coerceIn(0f, 1f))
    }

    /** Sets the player volume only when it actually changed (avoids redundant calls every tick). */
    private fun setPlayerVolume(volume: Float) {
        if (volume == lastAppliedVolume) return
        lastAppliedVolume = volume
        runCatching { player.volume = volume }
    }

    // ---- Queue (source of truth for order) → player timeline ----

    private fun syncFromQueue(q: PlaybackQueue) {
        val desiredIds = q.items.map { it.id }
        val playerIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

        // A pending session restore seeds the saved queue at the saved index + position (paused —
        // playWhenReady stays false) **before** any diff logic. The player is empty here, which the
        // diff below would treat as a plain append and drop the saved cursor/position on the floor.
        pendingRestore?.let { restore ->
            if (desiredIds.isEmpty()) return
            pendingRestore = null
            applyingQueue = true
            try {
                val items = q.items.map { it.toTimelineMediaItem() }
                player.setMediaItems(
                    items,
                    restore.currentIndex.coerceIn(0, items.lastIndex),
                    restore.positionMs.coerceAtLeast(0L),
                )
                player.prepare()
            } finally {
                applyingQueue = false
            }
            return
        }

        if (desiredIds == playerIds) {
            // Same items — only realign the cursor if it diverged from the player.
            val idx = q.currentIndex
            if (idx in desiredIds.indices && player.currentMediaItemIndex != idx) {
                applyingQueue = true
                player.seekTo(idx, C.TIME_UNSET)
                applyingQueue = false
            }
            return
        }

        applyingQueue = true
        try {
            when {
                desiredIds.isEmpty() -> player.clearMediaItems()

                // Pure append (the common "add to queue" case) — leaves the current item undisturbed.
                playerIds.size < desiredIds.size && desiredIds.subList(0, playerIds.size) == playerIds -> {
                    val tail = q.items.subList(playerIds.size, q.items.size).map { it.toTimelineMediaItem() }
                    player.addMediaItems(tail)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                }

                // Structural change (remove/reorder) — rebuild, keeping the current item's position.
                else -> {
                    val currentId = player.currentMediaItem?.mediaId
                    val keepIndex = desiredIds.indexOf(currentId)
                    val items = q.items.map { it.toTimelineMediaItem() }
                    if (keepIndex >= 0) {
                        player.setMediaItems(items, keepIndex, player.currentPosition)
                    } else {
                        player.setMediaItems(items, q.currentIndex.coerceIn(0, items.lastIndex), 0L)
                    }
                    player.prepare()
                }
            }
        } finally {
            applyingQueue = false
        }
    }

    // ---- Player truth → queue cursor + item status ----

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            // Mirrored into a field because the cache completer runs on an IO thread, and ExoPlayer
            // throws if its state is read from anywhere but the thread that owns it.
            buffering = state == Player.STATE_BUFFERING
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (applyingQueue) return
            // Arm a fade-in only for a natural transition (track ended → next, or repeat) — never a manual
            // skip, so pressing next snaps straight to full volume instead of ramping up.
            fadeInArmed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            val idx = player.currentMediaItemIndex
            val current = queue.state.value
            if (idx in current.items.indices && idx != current.currentIndex) {
                queue.goToIndex(idx)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A manual scrub shouldn't re-trigger a fade-in if the cursor lands back inside the fade window.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) fadeInArmed = false
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Keep the persisted position fresh: tick while playing, capture the exact spot on pause.
            if (isPlaying) startSaveTicker() else { stopSaveTicker(); persist() }

            // Record the actually-playing track as recently played (Phase 15). Firing on real playback
            // (not just a timeline change) is the accurate "you listened to this" signal; dedup by
            // identity means resumes just bump the timestamp. Guarded so storage can't crash playback.
            if (!isPlaying) return
            queue.state.value.current?.let { current ->
                hlsRetried -= current.id // it's playing now; allow a fresh HLS retry if rebuilt later
                scope.launch { runCatching { recentlyPlayed.record(current.track) } }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: queue.state.value.current?.id ?: return
            // Drop any cached (possibly expired) URL for this content so the next attempt re-resolves fresh.
            queue.state.value.items.firstOrNull { it.id == id }
                ?.let { streamResolver.invalidate(it.track.source.identityKey) }
            // An HLS stream (e.g. SoundCloud) resolved through the placeholder plays as a *progressive*
            // source (the ResolvingDataSource only rewrites the URL, not the source type) and fails.
            // Swap in an HLS-typed MediaItem so ExoPlayer builds an HlsMediaSource, then retry once.
            val stream = streamResolver.resolvedStreamFor(id)
            if (stream != null && stream.protocol == fm.rizx.player.domain.model.StreamProtocol.HLS && id !in hlsRetried) {
                hlsRetried += id
                val idx = player.currentMediaItemIndex
                if (idx >= 0 && player.getMediaItemAt(idx).mediaId == id) {
                    val hlsItem = MediaItem.Builder()
                        .setMediaId(id)
                        .setUri(stream.url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                    applyingQueue = true
                    player.replaceMediaItem(idx, hlsItem)
                    applyingQueue = false
                    player.prepare()
                    player.play()
                    return
                }
            }
            queue.updateItemState(id, QueueItemStatus.ERROR, error.errorCodeName)
        }
    }

    private fun sessionActivityIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    // ---- Notification custom buttons: favorite (heart) + repeat, matching the reference layout ----

    /** Builds the two custom notification buttons from the mirrored [currentFavorite] / [currentRepeat]. */
    private fun buildCustomLayout(): List<CommandButton> {
        val favorite = CommandButton.Builder()
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setDisplayName(if (currentFavorite) "Remove from liked" else "Like")
            .setIconResId(if (currentFavorite) R.drawable.ic_media_favorite_filled else R.drawable.ic_media_favorite)
            .setEnabled(true)
            .build()
        val repeat = CommandButton.Builder()
            .setSessionCommand(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
            .setDisplayName("Repeat")
            .setIconResId(if (currentRepeat == RepeatMode.ONE) R.drawable.ic_media_repeat_one else R.drawable.ic_media_repeat)
            .setEnabled(true)
            .build()
        return listOf(favorite, repeat)
    }

    /** Session callback: grants the two custom commands and handles their taps from the notification. */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            // Grant our two custom commands on top of whatever the default connection allows — never narrow
            // the UI controller's rights, just add heart/repeat so the notification controller can fire them.
            val sessionCommands = base.availableSessionCommands.buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_FAVORITE -> queue.state.value.current?.track?.let { track ->
                    scope.launch { runCatching { favorites.toggleTrack(track) } }
                }
                ACTION_CYCLE_REPEAT -> {
                    val next = when (queue.state.value.repeatMode) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                    queue.setRepeatMode(next)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        /** How often to snapshot the position while playing (bounds resume drift after a process kill). */
        const val SAVE_INTERVAL_MS = 5_000L

        /** Long enough that the song the user just pressed play on owns the bandwidth it needs. */
        const val FIRST_COMPLETION_DELAY_MS = 90_000L

        /** Re-check periodically: songs become half-cached as the session goes on. */
        const val COMPLETION_INTERVAL_MS = 10 * 60_000L

        /** Refill the radio when the cursor is within this many items of the end. */
        const val RADIO_REFILL_AHEAD = 2

        /** Radio queues stop refilling at this size — with Repeat ALL the tail is re-entered forever,
         *  which would otherwise grow the queue without bound. */
        const val RADIO_MAX_QUEUE = 200

        /** Crossfade fade length (ms) applied at automatic track transitions. */
        const val CROSSFADE_MS = 2_000L

        /** Short fade (ms) applied when Gapless is off — a perceptible gap between tracks. */
        const val GAP_FADE_MS = 350L

        /** How often the volume envelope re-evaluates while a fade is active. */
        const val FADE_TICK_MS = 75L

        /** Custom notification-button actions (favorite + repeat) invoked through the session. */
        const val ACTION_TOGGLE_FAVORITE = "fm.rizx.player.action.TOGGLE_FAVORITE"
        const val ACTION_CYCLE_REPEAT = "fm.rizx.player.action.CYCLE_REPEAT"
    }
}
