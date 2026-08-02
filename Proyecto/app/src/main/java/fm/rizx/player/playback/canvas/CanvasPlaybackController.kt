package fm.rizx.player.playback.canvas

import android.content.Context
import android.os.SystemClock
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasQuality

/**
 * The muted, looping video player behind the artwork.
 *
 * **A second ExoPlayer, deliberately.** `PlaybackService` owns the audio player — it drives the
 * MediaSession, audio focus, the notification, the queue timeline and the visualiser tap. Pushing a
 * decorative video through it would break every one of those. This one is muted, its audio track is
 * switched off, it plays a single item on repeat, and it is released the moment Now Playing goes away.
 * That is the documented exception to the "one ExoPlayer" rule; see
 * `docs/adr/0017-canvas-provider-and-network-policy.md`.
 *
 * It lives here rather than in a ViewModel or a Composable because the project forbids touching ExoPlayer
 * from Compose: the screen hands over a surface and never sees the player.
 */
@OptIn(UnstableApi::class)
class CanvasPlaybackController(private val context: Context) {

    /** What the surface should be showing. */
    enum class State { IDLE, BUFFERING, PLAYING }

    /** What Media3 actually decoded — the only honest source for these numbers. */
    data class VideoInfo(
        val width: Int?,
        val height: Int?,
        val frameRate: Float?,
        val firstFrameMs: Long?,
    )

    private var player: ExoPlayer? = null
    private var surface: TextureView? = null

    /** The remaining candidates, current one first. */
    private var queue: List<CanvasCandidate> = emptyList()
    private var triedFallback = false
    private var visible = true
    private var quality: CanvasQuality = CanvasQuality.DATA_SAVER
    private var preparedAtMs = 0L
    private var firstFrameMs: Long? = null

    private val current: CanvasCandidate? get() = queue.firstOrNull()

    /** Called when the state changes; the screen uses it to drive the fade. */
    var onState: (State) -> Unit = {}

    /** Called once per candidate, when its first frame lands. */
    var onVideoInfo: (VideoInfo) -> Unit = {}

    /**
     * Called when every candidate has failed, naming the provider that produced them — the caller's cue
     * to ask the repository for a second opinion, once.
     */
    var onExhausted: (providerId: String?) -> Unit = {}

    private var state: State = State.IDLE
        set(value) {
            if (field == value) return
            field = value
            onState(value)
        }

    /**
     * Points the player at [candidates], best first, from the beginning.
     *
     * Nothing is revealed yet: the state stays [State.BUFFERING] until a frame has actually been
     * rendered, so a slow or dead stream leaves the cover in place instead of flashing a black rectangle
     * over it.
     */
    fun prepare(candidates: List<CanvasCandidate>, quality: CanvasQuality) {
        if (candidates.isEmpty()) {
            clear()
            return
        }
        queue = candidates
        this.quality = quality
        triedFallback = false
        firstFrameMs = null
        state = State.BUFFERING
        play(candidates.first().mediaUrl)
    }

    /** Hands over the surface. Safe before [prepare] — [build] re-attaches whatever was handed over. */
    fun attach(view: TextureView) {
        surface = view
        player?.setVideoTextureView(view)
    }

    /**
     * Whether the canvas is on screen and the app is in front.
     *
     * Invisible means **stop**, not pause-and-keep-buffering: a decorative video must not hold a decoder
     * or pull bytes while the user is somewhere else. Setting `playWhenReady = false` on its own would
     * leave ExoPlayer topping up its buffer in the background, which is exactly what has to stop.
     */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        val p = player ?: return
        if (visible) {
            if (current == null) return
            p.playWhenReady = true
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
        } else {
            p.playWhenReady = false
            p.stop()
            state = State.IDLE
        }
    }

    /** Drops the video and goes back to the static cover. */
    fun clear() {
        queue = emptyList()
        triedFallback = false
        player?.stop()
        player?.clearMediaItems()
        state = State.IDLE
    }

    fun release() {
        player?.release()
        player = null
        surface = null
        queue = emptyList()
        state = State.IDLE
    }

    private fun play(url: String) {
        val p = player ?: build().also { player = it }
        // Apple's motion artwork is a multi-variant HLS ladder (360² → 2160²), so the network budget has
        // to be spent here, in track selection — there is one URL and ExoPlayer chooses inside it. A
        // progressive MP4 from YouTube ignores this, having had its cap applied when the URL was picked.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(quality.maxHeight, quality.maxHeight)
            .setMaxVideoBitrate(quality.maxHeight * BITRATE_PER_LINE)
            .build()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        preparedAtMs = SystemClock.elapsedRealtime()
        p.playWhenReady = visible
    }

    /**
     * The candidate's spare URL, then the next candidate, then give up quietly.
     *
     * Decoration must never surface an error or interrupt the music, and it must never retry in a loop
     * either — a dead link retried forever is a background download nobody asked for. Walking the list
     * is bounded by its length; each candidate gets its own single spare.
     */
    private fun onError() {
        val candidate = current
        val spare = candidate?.fallbackUrl
        when {
            !triedFallback && spare != null -> {
                triedFallback = true
                state = State.BUFFERING
                play(spare)
            }
            queue.size > 1 -> {
                queue = queue.drop(1)
                triedFallback = false
                state = State.BUFFERING
                play(queue.first().mediaUrl)
            }
            else -> {
                val providerId = candidate?.providerId
                clear()
                onExhausted(providerId)
            }
        }
    }

    private fun onFirstFrame() {
        if (visible) state = State.PLAYING
        // Measured once per canvas. `onRenderedFirstFrame` fires again whenever the surface is
        // re-attached — leaving the screen and coming back — and re-measuring against the original
        // prepare() turned a 1.6 s reading into a 22 s one.
        if (firstFrameMs == null) firstFrameMs = SystemClock.elapsedRealtime() - preparedAtMs
        reportVideoInfo()
    }

    /**
     * The decoded size and rate, re-read whenever ExoPlayer changes variant.
     *
     * Reporting only at the first frame would be honest but useless on an adaptive ladder: Apple's HLS
     * starts at its 360² rung and climbs, so the panel would say 360×360 no matter which quality was
     * chosen. What the user wants to know is the rung it *settled* on.
     */
    private fun reportVideoInfo() {
        // Only when there is something to say. `onVideoSizeChanged` also fires on stop(), with no format
        // behind it — reporting that would wipe a good reading the instant the screen was left, which is
        // exactly when someone goes to Settings to look at it.
        val format: Format = player?.videoFormat?.takeIf { it.width > 0 && it.height > 0 } ?: return
        onVideoInfo(
            VideoInfo(
                width = format.width,
                height = format.height,
                // Format.NO_VALUE is -1. Reported rather than assumed: every rung of Apple's ladder and
                // YouTube's muxed stream is 30 fps, so a "60 FPS" claim would be one this app can't make.
                frameRate = format.frameRate.takeIf { it > 0f },
                firstFrameMs = firstFrameMs,
            ),
        )
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        // ~5 s instead of ExoPlayer's default ~50 s. This is a short silent loop under a scrim: the
        // default buffer would pull tens of megabytes for a video nobody watches closely, on top of the
        // audio stream the same song is already streaming. The single biggest data saving in the feature.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .build(),
        )
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            // The muxed stream carries an audio track we will never hear — YouTube's video-only
            // progressive URLs are throttled into a timeout, so the muxed one is the only option.
            // Muting silences that track; disabling the type stops it being decoded at all.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            surface?.let { setVideoTextureView(it) }
            addListener(object : Player.Listener {
                // Only now is there something to fade in. Announcing "playing" at prepare() time — which
                // the first version of this did — uncovers the surface while it is still black.
                override fun onRenderedFirstFrame() = onFirstFrame()

                // The ladder climbs after the first frame; this is what tells the panel where it landed.
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (firstFrameMs != null) reportVideoInfo()
                }

                override fun onPlayerError(error: PlaybackException) = onError()
            })
        }

    private companion object {
        const val MIN_BUFFER_MS = 1_000
        const val MAX_BUFFER_MS = 5_000
        const val PLAYBACK_BUFFER_MS = 500
        const val REBUFFER_MS = 1_000
        const val TARGET_BUFFER_BYTES = 2 * 1024 * 1024

        /**
         * Bitrate ceiling per line of resolution, as a secondary guard: the size cap is what actually
         * picks the variant, this only stops a heavier codec rendition of those same dimensions.
         *
         * Generous on purpose, and **more generous since the quality tiers grew**. Apple declares
         * `BANDWIDTH` as a peak rather than an average — its 360² rung says 293 kbps for a 260 kbps
         * stream, and its 1080² rung says 4.6 Mbps. At the old 1500/line a "High" cap of 1080 came out
         * at 1.62 Mbps, which is below that rung: the size cap said 1080 and the bitrate cap quietly
         * held it at 486², making the Settings caption a promise the code could not keep. 5000/line
         * clears every rung at its own tier and still stops a heavier codec rendition of those same
         * dimensions, which is all this was ever meant to do.
         */
        const val BITRATE_PER_LINE = 5_000
    }
}
