package fm.rizx.player.ui.screens

import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.domain.model.AudioFormatUi
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.PlaybackQueue
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.animateFloatAsState
import android.content.res.Configuration
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fm.rizx.player.R
import fm.rizx.player.core.formatClock
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.PlayerDotTrail
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.TransportButton
import fm.rizx.player.ui.components.TransportMarker
import fm.rizx.player.ui.components.TransportPlayButton
import fm.rizx.player.ui.components.VerticalLabel
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.usecase.LinkedArtist
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.brutalShadow
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.dot
import fm.rizx.player.ui.theme.dotGrid
import fm.rizx.player.ui.theme.isLargeScreenDevice
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.rememberRizxHaptics
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    durationSec: Int,
    liked: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLike: () -> Unit,
    onSeek: (Float) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenLyrics: () -> Unit,
    /** Opens the system audio-output switcher (phone speaker · Bluetooth · Cast/nearby devices). */
    onOpenDevices: () -> Unit = {},
    /** Starts an endless radio seeded from the current song (the service auto-fills similar tracks). */
    onStartRadio: () -> Unit = {},
    /**
     * The billed artists, already resolved to the pages they open. Empty falls back to plain [artist]
     * text; an entry with a null `source` renders but stays untappable.
     */
    artistLinks: List<LinkedArtist> = emptyList(),
    /** Opens one artist's page. */
    onOpenArtist: (ProviderRef) -> Unit = {},
    album: String = "",
    /**
     * What is actually decoding, when anything is known about it — `FLAC · 16-bit · 48 kHz`.
     *
     * Null hides the line entirely, which is the honest state for a song nobody measured: the setting is
     * off, the stream resolver had nothing to say, or the next track hasn't resolved yet.
     */
    audioFormat: AudioFormatUi? = null,
    trackIndex: Int = 0,
    trackCount: Int = 1,
    // A mode, not a flag: the chip has to tell "repeat the queue" apart from "repeat this song".
    repeatMode: RepeatMode = RepeatMode.OFF,
    shuffleOn: Boolean = false,
    onToggleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    /**
     * Hands the video surface to whoever owns the canvas player — null when there's no video to show.
     * A callback rather than the player itself: ExoPlayer is never touched from a Composable.
     */
    canvasVideo: ((TextureView) -> Unit)? = null,
    canvasPlaying: Boolean = false,
    /** The overflow menu, hoisted so this screen stays free of repositories. */
    menu: @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = { _, _ -> },
    /** The live queue, for the pull-up "Up next" drawer. */
    queue: PlaybackQueue = PlaybackQueue(),
    /** Jump to a queue item (by its per-insertion id) — makes it the current song and plays it. */
    onPlayQueueItem: (String) -> Unit = {},
    /** Removes a queue item (by id) from the "Up next" drawer. */
    onRemoveQueueItem: (String) -> Unit = {},
    /** Reorders the queue by **absolute** indices — the drawer maps its upcoming rows to these. */
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    // True while the current track is resolving/buffering to play — shows a loader on the play button.
    loading: Boolean = false,
    // Live audio spectrum (0..1 per bar) read lazily inside the waveform's draw so only it invalidates.
    levels: () -> FloatArray = { FloatArray(0) },
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    // Live offsets for the artwork gestures — the finger drives them directly, then a one-shot `animate`
    // settles them back (never a continuous driver, so audio stays clean).
    var artDragX by remember { mutableStateOf(0f) }   // horizontal cover swipe → prev/next
    var screenDragY by remember { mutableStateOf(0f) } // vertical swipe-down → dismiss
    // Bumped on a double-tap-to-like so the heart stamp replays; a change trigger, not an animation loop.
    var likeStamp by remember { mutableStateOf(0) }
    // The songs lined up after the current one — what the drawer lists.
    val upcoming = remember(queue) {
        if (queue.currentIndex < 0) emptyList()
        else queue.items.drop(queue.currentIndex + 1)
    }
    // Monochrome player (matches the reference): the accent is the theme ink/ivory — the life comes
    // from the animated dot-matrix field behind the controls, not a colour aurora sampled from art.
    val npAccent = c.accent
    val npOnFill = c.onFill
    val npTextShadow = if (c.isDark) Shadow(color = Color.Black.copy(alpha = 0.35f), blurRadius = 12f) else Shadow(Color.Transparent)

    // One-shot "rise" of the waveform on entry (bars grow up from the baseline).
    val waveGrow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { waveGrow.animateTo(1f, animationSpec = tween(520, easing = FastOutSlowInEasing)) }

    val heroBtnBg = if (c.isDark) Color(0xFF0A0A0B).copy(alpha = 0.5f) else Color(0xFFF3F0E9).copy(alpha = 0.58f)
    val heroBtnLine = if (c.isDark) Color.White.copy(alpha = 0.14f) else Color(0xFF221F1A).copy(alpha = 0.18f)

    // Deterministic waveform bar heights (0..1), matching the design's seeded formula.
    val barHeights = remember {
        val n = 58
        List(n) { i ->
            val seed = abs(sin((i + 1) * 12.9898) * 43758.5453)
            val rnd = seed - floor(seed)
            val env = 0.42 + 0.58 * Math.pow(sin(((i + 0.5) / n) * Math.PI), 0.45)
            ((0.16 + rnd * 0.84) * env).coerceIn(0.15, 1.0).toFloat()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
          // The artwork takes what's left **after** everything below it, not a fixed 420.dp.
          //
          // The waveform, times, title, transport, track actions, up-next handle and footer are all
          // fixed-height; when the artwork claimed 420 and that wasn't enough, the last of them were
          // clipped to nothing — on a 1220x2712 phone the add-to-playlist and like buttons simply
          // vanished between the transport row and "UP NEXT".
          //
          // [maxHeight] here is the **measured** space left after the status bar, which is the part the
          // earlier attempt got wrong: it derived the figure from `Configuration.screenHeightDp`, whose
          // relationship to the system bars varies, and reserved 380dp for a stack that really needs
          // ~412dp. Measuring removes both guesses. The 420 cap keeps a tall phone's artwork exactly as
          // designed; the reserve scales with the system font because that is what inflates those rows.
          val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
          // The cap rose with the footer's removal (420 → 470): on a tall phone the artwork was already
          // pinned at 420 *before* that row went, so freeing its 69dp would otherwise have left dead paper
          // above the up-next bar rather than a bigger cover. Short screens are unaffected — they never
          // reach the cap.
          val artHeight = (maxHeight - CONTROLS_RESERVE * fontScale).coerceIn(180.dp, 470.dp)
          // Side by side only where it is genuinely better: a landscape window on a device whose *shortest*
          // edge is tablet-sized, and only when it is tall enough for the whole control stack. A phone never
          // gets here — it stays upright — but the height check still matters, because a short landscape
          // window is better served by the stacked layout, which shrinks the artwork to fit.
          val twoPane = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
              isLargeScreenDevice() &&
              maxHeight >= CONTROLS_RESERVE * fontScale
          // Two ways to lay this screen out, from **one** definition of each half: stacked on a phone,
          // side by side where there is width for it. Local composable lambdas rather than extracted
          // functions — these two blocks read forty-odd pieces of state and a dozen callbacks between them,
          // and threading all of that through a parameter list would be a far larger change than the
          // layout it buys.
          val artwork: @Composable (Modifier) -> Unit = { artModifier ->
            // ---- Album art ----
            Box(
                artModifier
                    .clipToBounds()
                    .graphicsLayer { translationX = artDragX }
                    // Swipe the cover: horizontal = prev/next, a downward drag dismisses the player. One
                    // axis-locked drag node so the two never fight (and neither fights the waveform's own
                    // seek-drag, which is a separate region below). Double-tap-to-like is its own tap node.
                    .pointerInput(Unit) {
                        var axis = 0 // 0 undecided · 1 horizontal (skip) · 2 vertical (dismiss)
                        var settle: kotlinx.coroutines.Job? = null
                        detectDragGestures(
                            onDragStart = { settle?.cancel(); axis = 0 },
                            onDragEnd = {
                                if (axis == 1) {
                                    val t = size.width * 0.22f
                                    if (artDragX <= -t) { haptics.confirm(); onNext() }
                                    else if (artDragX >= t) { haptics.confirm(); onPrevious() }
                                    settle = scope.launch { animate(artDragX, 0f, animationSpec = tween(210, easing = FastOutSlowInEasing)) { v, _ -> artDragX = v } }
                                } else if (axis == 2) {
                                    if (screenDragY >= size.height * 0.30f) onBack()
                                    else settle = scope.launch { animate(screenDragY, 0f, animationSpec = tween(210, easing = FastOutSlowInEasing)) { v, _ -> screenDragY = v } }
                                }
                                axis = 0
                            },
                            onDragCancel = { artDragX = 0f; screenDragY = 0f; axis = 0 },
                        ) { change, delta ->
                            if (axis == 0) axis = if (abs(delta.x) >= abs(delta.y)) 1 else 2
                            change.consume()
                            if (axis == 1) artDragX += delta.x
                            else screenDragY = (screenDragY + delta.y).coerceAtLeast(0f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (!liked) onToggleLike()
                            likeStamp++
                            haptics.confirm()
                        })
                    },
            ) {
                // Procedural "cover": an aurora-tinted gradient stands in for album art.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(
                                if (c.isDark) androidx.compose.ui.graphics.lerp(Color(0xFF1A161E), npAccent, 0.22f) else c.heroA,
                                if (c.isDark) Color(0xFF0C0C11) else c.heroB,
                            ),
                        ),
                    ),
                )
                // Album artwork on top of the gradient base — real cover when available, else the sample.
                // Crossfaded on track change so next/prev dissolves instead of hard-cutting. Only the base
                // image fades; the scrim, canvas video and HUD above stay put (fading them would flicker).
                val albumArtworkDesc = stringResource(R.string.player_album_artwork)
                Crossfade(targetState = artworkUrl, animationSpec = tween(320), label = "coverArt", modifier = Modifier.fillMaxSize()) { url ->
                    if (url != null) {
                        coil.compose.AsyncImage(
                            model = url,
                            contentDescription = albumArtworkDesc,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.velvet_asphalt),
                            contentDescription = albumArtworkDesc,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // The canvas: animated cover art, muted and looping, fading in over the artwork once a
                // real frame has been decoded — so a track with no canvas, or a slow lookup, never
                // reveals anything and the cover stays put.
                //
                // The surface is created as soon as there is a player to attach it to, and only its
                // *alpha* follows [canvasPlaying]. Gating the AndroidView itself on the fade deadlocks:
                // the fade waits for the first rendered frame, and the first frame can never be rendered
                // because there is no surface to render it onto.
                if (canvasVideo != null) {
                    val fade by animateFloatAsState(
                        targetValue = if (canvasPlaying) 1f else 0f,
                        animationSpec = tween(600),
                        label = "canvasFade",
                    )
                    AndroidView(
                        factory = { ctx -> TextureView(ctx).also(canvasVideo) },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade },
                    )
                }
                // Bottom scrim — fades the cover into the zone below (paper in light, the wash in dark).
                //
                // Trimmed twice on Paper (0.6 → 0.8 → 0.88): its only job is to blend the seam where the
                // cover meets the paper, and spread over the bottom 40% it read as a pale haze *on the
                // artwork* — loudest on a dark cover, where it looked like a glow rising out of the bottom
                // edge. It is now a 12% strip: enough to soften the join, not enough to veil the art. Ivory
                // keeps 0.6, where the same gradient ends at 30% of a near-black background and barely
                // registers.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            (if (c.isDark) 0.6f else 0.88f) to Color.Transparent,
                            1.0f to if (c.isDark) c.bg.copy(alpha = 0.3f) else c.bg,
                        ),
                    ),
                )
                // HUD chrome over the art (ref #4): corner brackets + a serial/track code.
                Box(
                    Modifier.matchParentSize().cornerBrackets(
                        if (c.isDark) Color.White.copy(alpha = 0.5f) else c.heroText.copy(alpha = 0.45f),
                        len = 16.dp,
                        inset = 12.dp,
                    ),
                )
                CodeLabel(
                    "REC / TRK ${trackIndex + 1}-${trackCount.coerceAtLeast(1)}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp),
                    color = if (c.isDark) Color.White.copy(alpha = 0.6f) else c.heroText.copy(alpha = 0.6f),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    RizxIconButton(RizxIcons.Back, stringResource(R.string.player_back), onBack, background = heroBtnBg, border = heroBtnLine, tint = if (c.isDark) Color.White else c.heroText)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RizxIconButton(RizxIcons.Lyrics, stringResource(R.string.player_lyrics), onOpenLyrics, background = heroBtnBg, border = heroBtnLine, tint = if (c.isDark) Color.White else c.heroText)
                        Box {
                            RizxIconButton(
                                RizxIcons.MoreVert, stringResource(R.string.player_more_options), { menuOpen = true },
                                background = heroBtnBg, border = heroBtnLine,
                                tint = if (c.isDark) Color.White else c.heroText,
                            )
                            menu(menuOpen) { menuOpen = false }
                        }
                    }
                }
                // Double-tap-to-like feedback: a red heart stamps over the cover, then fades. One-shot.
                LikeStamp(trigger = likeStamp)
            }
          }
          val controlsZone: @Composable (Modifier) -> Unit = { zoneModifier ->
            // ---- Controls zone ----
            // Dark: a smooth wash of the album's colours (purple -> coral -> dark) flowing down
            // from the cover. Light/Paper: nothing — the plain paper background, like the reference.
            Box(zoneModifier) {
            // On a wide pane these rows would stretch to the full 1280dp: the transport would sit at the
            // screen's extremes and the title would swim in the middle of nothing. Capped and centred.
            val paneWidth = if (twoPane) {
                Modifier.widthIn(max = PANE_CONTENT_MAX).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            }
                // Nothing-OS dot-matrix texture behind the controls — a STATIC grid (drawn only on
                // recomposition, not a 60fps driver), so it never contends with audio decode/output on
                // low-end GPUs / emulators. Both themes.
                Box(
                    Modifier
                        .matchParentSize()
                        .dotGrid(
                            color = if (c.isDark) c.dotOn.copy(alpha = 0.17f) else c.accent.copy(alpha = 0.15f),
                            spacing = 30.dp,
                            dotRadius = 2.2.dp,
                        ),
                )
                // Faint dots drifting between the grid points with a short comet trail (audio-safe ~15fps).
                PlayerDotTrail(
                    color = if (c.isDark) c.dotOn.copy(alpha = 0.45f) else c.accent.copy(alpha = 0.40f),
                    modifier = Modifier.matchParentSize(),
                )
                if (c.isDark) {
                    // Legibility scrim: slight top darken, fade to the footer.
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0.0f to c.bg.copy(alpha = 0.16f),
                                0.42f to Color.Transparent,
                                1.0f to c.bg,
                            ),
                        ),
                    )
                }
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Centred in the pane on a wide screen: with the artwork beside the stack rather than
                    // above it, the controls are a third of the height and pinning them to the top leaves a
                    // screen of empty paper underneath.
                    verticalArrangement = if (twoPane) Arrangement.Center else Arrangement.Top,
                ) {
                    // ---- Waveform scrubber (tap or drag to seek) ----
                    // Local drag override so the playhead follows the finger instantly, before the polled
                    // position round-trips back through the player (same trick as the mini-player). Read
                    // only in the draw phase below, so a drag redraws just the waveform, not the screen.
                    var drag by remember { mutableStateOf<Float?>(null) }
                    BoxWithConstraints(
                        paneWidth
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .height(WAVEFORM_HEIGHT)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    haptics.select()
                                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                                }
                            }
                            .pointerInput(barHeights.size) {
                                var lastBar = -1
                                detectHorizontalDragGestures(
                                    onDragEnd = { drag = null },
                                    onDragCancel = { drag = null },
                                ) { change, _ ->
                                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                                    drag = frac
                                    val bar = (frac * barHeights.size).toInt()
                                    if (bar != lastBar) {
                                        haptics.select()
                                        lastBar = bar
                                    }
                                    onSeek(frac)
                                }
                            },
                    ) {
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleY = waveGrow.value
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                },
                        ) {
                            // Read the live spectrum here (draw phase) so only this waveform redraws (~25fps),
                            // not the whole screen. Falls back to the seeded shape before any audio arrives.
                            val live = levels()
                            val reactive = live.size >= 8
                            val n = if (reactive) live.size else barHeights.size
                            val gap = 2.dp.toPx()
                            val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                            val playedX = size.width * (drag ?: progress).coerceIn(0f, 1f)
                            for (i in 0 until n) {
                                val raw = if (reactive) live[i] else barHeights[i]
                                val h = raw.coerceIn(0.04f, 1f) * size.height
                                val x = i * (barW + gap)
                                val played = x + barW / 2f <= playedX
                                drawRect(
                                    color = if (played) npAccent else c.waveTrack,
                                    topLeft = Offset(x, size.height - h),
                                    size = Size(barW, h),
                                )
                            }
                            // Contrasting playhead: a bright bar flanked by thin background edges + a bold
                            // handle, so the time selector stays legible against both bright and dim bars.
                            val hx = playedX.coerceIn(0f, size.width)
                            val lineW = 3.dp.toPx()
                            val edge = 1.5.dp.toPx()
                            drawRect(c.bg, Offset(hx - lineW / 2f - edge, 0f), Size(lineW + edge * 2f, size.height))
                            drawRect(c.redAccent, Offset(hx - lineW / 2f, 0f), Size(lineW, size.height))
                            val hs = 13.dp.toPx()
                            drawRect(c.bg, Offset(hx - hs / 2f - edge, 0f), Size(hs + edge * 2f, hs + edge))
                            drawRect(c.redAccent, Offset(hx - hs / 2f, 0f), Size(hs, hs))
                        }
                        // Floating dot-matrix time chip that tracks the finger while scrubbing — precision
                        // feedback, gone the instant you lift (bounded by the drag, no continuous driver).
                        val scrubbing = drag
                        if (scrubbing != null) {
                            val chipW = 56.dp
                            val x = (maxWidth * scrubbing.coerceIn(0f, 1f) - chipW / 2)
                                .coerceIn(0.dp, (maxWidth - chipW).coerceAtLeast(0.dp))
                            ScrubBubble(
                                formatClock(scrubbing * durationSec.toDouble()),
                                modifier = Modifier.align(Alignment.TopStart).offset(x = x, y = (-30).dp),
                            )
                        }
                    }

                    // ---- Time row: elapsed · "N OF M" · remaining (dot-matrix numerals) ----
                    Row(
                        paneWidth.padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Duration/elapsed a touch larger (+1.5sp) and heavier (Black) per request.
                        Text(
                            formatClock(progress * durationSec.toDouble()),
                            style = dot(12, FontWeight.Black).copy(fontSize = 13.5.sp),
                            color = c.text,
                        )
                        Text(
                            "${(trackIndex + 1).coerceAtLeast(1)} OF ${trackCount.coerceAtLeast(1)}",
                            style = dot(11, FontWeight.Medium, 0.12f),
                            color = c.muted,
                        )
                        Text(
                            "-" + formatClock((durationSec - progress * durationSec).toDouble()),
                            style = dot(12, FontWeight.Black).copy(fontSize = 13.5.sp),
                            color = c.text2,
                        )
                    }

                    // Title + artist cross-fade/slide when the track changes, so next/prev feels intentional
                    // instead of a hard swap. Keyed by the text pair; one-shot, so no continuous driver.
                    AnimatedContent(
                        targetState = title to artist,
                        transitionSpec = {
                            (fadeIn(tween(280)) + slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith
                                (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
                        },
                        label = "trackText",
                        modifier = paneWidth,
                    ) { (animTitle, animArtist) ->
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                animTitle,
                                style = sg(26, FontWeight.Bold, -0.02f).copy(shadow = npTextShadow),
                                color = c.text,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                // Marquee: a title too long for one line scrolls leftward instead of clipping.
                                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                            )
                            ArtistLine(
                                fallback = animArtist,
                                links = artistLinks,
                                onOpenArtist = onOpenArtist,
                                shadow = npTextShadow,
                            )
                            AudioFormatLine(audioFormat, npTextShadow)
                        }
                    }

                    // ---- Controls ----
                    // All five share one square-framed idiom now, and the three that hold a state — shuffle,
                    // play/pause, repeat — carry the red corner marker that says which state they are in.
                    // Skip has none, because it has none to be wrong about. See [TransportButton].
                    //
                    // The inset is tighter than the rows around it on purpose: five controls at a uniform
                    // 48.dp plus the play block still have to fit a 320.dp-wide screen, which the old mix of
                    // 46, 50 and 92.dp did not.
                    Row(
                        paneWidth.padding(horizontal = TRANSPORT_INSET, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Shuffle and repeat flank the transport in the order every player uses. They used to
                        // sit in the footer's opposite corners, split by the artist text — playback *modes*
                        // divorced from the playback controls, which made them easy to miss.
                        TransportButton(
                            icon = RizxIcons.Shuffle,
                            contentDescription = if (shuffleOn) stringResource(R.string.player_shuffle_on_desc) else stringResource(R.string.player_shuffle_off_desc),
                            onClick = onToggleShuffle,
                            iconSize = 22.dp,
                            marker = if (shuffleOn) TransportMarker.On else TransportMarker.Off,
                        )
                        TransportButton(
                            RizxIcons.SkipPrevious,
                            stringResource(R.string.player_previous),
                            onPrevious,
                            iconSize = 28.dp,
                            nudge = -1,
                        )
                        TransportPlayButton(
                            isPlaying = isPlaying,
                            onClick = onTogglePlay,
                            fillColor = npAccent,
                            onFillColor = npOnFill,
                            loading = loading,
                        )
                        TransportButton(
                            RizxIcons.SkipNext,
                            stringResource(R.string.player_next),
                            onNext,
                            iconSize = 28.dp,
                            nudge = 1,
                        )
                        TransportButton(
                            icon = if (repeatMode == RepeatMode.ONE) RizxIcons.RepeatOne else RizxIcons.Repeat,
                            contentDescription = when (repeatMode) {
                                RepeatMode.OFF -> stringResource(R.string.player_repeat_off_desc)
                                RepeatMode.ALL -> stringResource(R.string.player_repeat_all_desc)
                                RepeatMode.ONE -> stringResource(R.string.player_repeat_one_desc)
                            },
                            onClick = onToggleRepeat,
                            iconSize = 22.dp,
                            marker = if (repeatMode != RepeatMode.OFF) TransportMarker.On else TransportMarker.Off,
                        )
                    }

                    // Track actions sit in their own row so the transport above stays purely playback. Same
                    // horizontal padding and SpaceBetween as that row, and the same 46.dp button, so these
                    // land squarely under shuffle (left) and repeat (right) instead of floating loose —
                    // and the bottom bar below repeats the pair, so all four share two vertical axes.
                    Row(
                        paneWidth.padding(horizontal = ACTION_INSET, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        GlassButton(RizxIcons.PlaylistAdd, stringResource(R.string.player_add_to_playlist), onAddToPlaylist, c.isDark, size = ACTION_BUTTON, iconSize = ACTION_ICON)
                        GlassButton(
                            if (liked) RizxIcons.Favorite else RizxIcons.FavoriteBorder,
                            // State-aware: the old constant "Like" told a screen-reader user nothing about
                            // whether the song was already liked.
                            if (liked) stringResource(R.string.player_remove_from_liked) else stringResource(R.string.player_like),
                            onToggleLike,
                            c.isDark,
                            size = ACTION_BUTTON,
                            iconSize = ACTION_ICON,
                            tint = if (liked) c.redAccent else null,
                        )
                    }

                    // Pushes the stack to the top in portrait; in the centred wide pane it would take
                    // all the slack and there would be nothing left to centre.
                    if (!twoPane) Spacer(Modifier.weight(1f))
                }
                // Rotated technical label running up the left edge (refs #3/#4).
                VerticalLabel(
                    "NOW PLAYING",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    color = c.muted,
                )
            }
          }

          if (twoPane) {
              // Landscape on a tablet or an unfolded foldable — the only devices that reach it, because a
              // phone stays upright (MainActivity.applyOrientationPolicy). The artwork takes the left half
              // at its natural square and the whole control stack takes the right, so neither is the
              // letterboxed strip that a rotated portrait layout gives you.
              Row(Modifier.fillMaxSize().graphicsLayer { translationY = screenDragY }) {
                  artwork(Modifier.weight(1f).fillMaxHeight())
                  Column(Modifier.weight(1f).fillMaxHeight()) {
                      controlsZone(Modifier.fillMaxWidth().weight(1f))
                  // ---- Bottom action bar: nearby devices · up-next peek · radio ----
                  // The two new actions share the drawer's strip so they read as one bar instead of floating over
                  // the controls. The peek in the middle still pulls up the queue drawer, and only appears when
                  // there's actually something queued.
                  NowPlayingBottomBar(
                      upcomingCount = upcoming.size,
                      onOpenQueue = { queueOpen = true },
                      onOpenDevices = onOpenDevices,
                      onStartRadio = onStartRadio,
                  )
                  }
              }
          } else {
          Column(Modifier.fillMaxSize().graphicsLayer { translationY = screenDragY }) {
            artwork(Modifier.fillMaxWidth().height(artHeight))
            controlsZone(Modifier.fillMaxWidth().weight(1f))

            // ---- Bottom action bar: nearby devices · up-next peek · radio ----
            // The two new actions share the drawer's strip so they read as one bar instead of floating over
            // the controls. The peek in the middle still pulls up the queue drawer, and only appears when
            // there's actually something queued.
            NowPlayingBottomBar(
                upcomingCount = upcoming.size,
                onOpenQueue = { queueOpen = true },
                onOpenDevices = onOpenDevices,
                onStartRadio = onStartRadio,
            )
          }
          }
        }

        // ---- Up-next drawer: slides up over the player, tap a song to jump, tap away to hide ----
        // Scrim first (behind the panel) so tapping the exposed player area collapses the drawer.
        androidx.compose.animation.AnimatedVisibility(
            visible = queueOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.bg.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { queueOpen = false } },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = queueOpen,
            enter = slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            UpNextPanel(
                upcoming = upcoming,
                baseIndex = queue.currentIndex + 1,
                onPlay = { id -> queueOpen = false; onPlayQueueItem(id) },
                onRemove = onRemoveQueueItem,
                onMove = onMoveQueueItem,
                onCollapse = { queueOpen = false },
            )
        }
    }
}

/**
 * The billing under the title, with **each artist its own destination**.
 *
 * A collaboration used to be one string and therefore one tap target, so the second artist was
 * unreachable — and since no catalogue has an artist called "Omar Courtz & De La Rose", usually the
 * first one was too. The names arrive already resolved ([LinkedArtist]); one that resolved to nothing
 * still renders, just without a tap.
 *
 * Names carry `weight(fill = false)`: they shrink to fit and ellipsize individually, so a long billing
 * can never push the row past the screen or move the controls below it.
 */
@Composable
private fun ArtistLine(
    fallback: String,
    links: List<LinkedArtist>,
    onOpenArtist: (ProviderRef) -> Unit,
    shadow: Shadow?,
) {
    val c = RizxTheme.colors
    val style = mr(14, FontWeight.Medium).copy(shadow = shadow)
    if (links.isEmpty()) {
        Text(
            fallback,
            style = style,
            color = c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        links.forEachIndexed { index, artist ->
            if (index > 0) {
                Text("·", style = style, color = c.text2.copy(alpha = 0.55f))
            }
            Text(
                artist.name,
                style = style,
                color = c.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(
                        // Tappable only where we know which artist this is: a name alone is not an identity.
                        artist.source
                            ?.let { ref -> Modifier.clickableScale(scale = 0.96f) { onOpenArtist(ref) } }
                            ?: Modifier,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * One line of monospace saying what is decoding: `FLAC · 16-bit · 48 kHz`, `OPUS · 160 kbps · 48 kHz`.
 *
 * Deliberately quiet — it is information, not a badge. A lossless codec gets the accent colour because
 * that is the fact worth spotting at a glance, and **it is decided by the codec, never by the bitrate**:
 * a large lossy file is still lossy.
 *
 * The word `BIT-PERFECT` appears nowhere in this app. Between Android's mixer, the automatic equalizer,
 * loudness normalisation and the crossfade envelope, the samples reaching the DAC are not the samples in
 * the file, and claiming otherwise would be the one dishonest line in a feature built on measuring things.
 */
@Composable
private fun AudioFormatLine(format: AudioFormatUi?, shadow: Shadow?) {
    val label = format?.shortLabel ?: return
    val c = RizxTheme.colors
    Text(
        label,
        style = code(11).copy(shadow = shadow),
        color = if (format.isLosslessCodec) c.accent else c.text2.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * The strip that sits at the drawer's height: nearby-devices on the left, the up-next peek in the middle,
 * and start-radio on the right. Giving the two new actions their own bar keeps the transport row above
 * purely about playback while putting cast and radio within easy thumb reach.
 *
 * Its horizontal padding and button size deliberately match the track-actions row above
 * ([ACTION_INSET] / [ACTION_BUTTON]), so devices sits exactly under add-to-playlist and radio exactly
 * under like — four buttons on two shared vertical axes rather than two rows that nearly line up.
 */
@Composable
private fun NowPlayingBottomBar(
    upcomingCount: Int,
    onOpenQueue: () -> Unit,
    onOpenDevices: () -> Unit,
    onStartRadio: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (c.isDark) Color(0xFF0C0C10).copy(alpha = 0.6f) else c.elev)
            // The artist/album readout that used to sit below this bar carried the navigation-bar inset;
            // with it gone, this is the last thing on the screen and inherits the job.
            .navigationBarsPadding()
            .padding(horizontal = ACTION_INSET, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left: opens the system audio-output switcher (speaker · Bluetooth · Cast/nearby devices).
        GlassButton(RizxIcons.Devices, stringResource(R.string.player_nearby_devices), onOpenDevices, c.isDark, size = ACTION_BUTTON, iconSize = ACTION_ICON)
        // Center: the up-next peek — tap or swipe up to open the queue drawer. Only when something's queued;
        // otherwise the two buttons just sit at the strip's ends.
        if (upcomingCount > 0) {
            UpNextHandle(count = upcomingCount, onOpen = onOpenQueue, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        // Right: start an endless radio seeded from this song.
        GlassButton(RizxIcons.Radio, stringResource(R.string.player_start_radio), onStartRadio, c.isDark, size = ACTION_BUTTON, iconSize = ACTION_ICON)
    }
}

/** A slim pull-up peek: the drawer's handle. Tap or swipe up to open. Sized to sit inside the bottom bar. */
@Composable
private fun UpNextHandle(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Column(
        modifier
            // Tap and swipe live in separate pointerInput nodes so they don't fight — a drag detector on
            // the same node as `clickable` swallows the tap.
            .pointerInput(Unit) { detectTapGestures { onOpen() } }
            .pointerInput(Unit) { detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -6f) onOpen() } }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The grab bar.
        Box(Modifier.width(36.dp).height(4.dp).background(c.muted.copy(alpha = 0.6f)))
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(RizxIcons.ChevronUp, null, tint = c.text2, modifier = Modifier.size(15.dp))
            CodeLabel(stringResource(R.string.player_up_next_count, count), size = 11)
        }
    }
}

/**
 * The drawer body: a drag-down handle, a header, and the upcoming songs — now a real queue manager
 * (tap to play, X to remove, long-press the grip to reorder).
 *
 * Reorder is built from scratch (no library): the lifted row follows the finger, and a **single** move is
 * committed on drop (`baseIndex + local` maps an upcoming row to its absolute queue index), then
 * `animateItem` settles the rest. Committing once, on drop, keeps mid-drag churn out of the queue repo.
 */
@Composable
private fun UpNextPanel(
    upcoming: List<fm.rizx.player.domain.model.QueueItem>,
    baseIndex: Int,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onCollapse: () -> Unit,
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(QUEUE_DRAWER_FRACTION)
            .background(c.elev)
            .border(1.dp, c.hardLine),
    ) {
        // Drag-down / tap handle to hide the drawer and return to the player.
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { onCollapse() } }
                .pointerInput(Unit) { detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 6f) onCollapse() } }
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(36.dp).height(4.dp).background(c.muted.copy(alpha = 0.6f)))
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.player_up_next), style = sg(17, FontWeight.Bold, -0.01f), color = c.text, modifier = Modifier.weight(1f))
                Icon(RizxIcons.ChevronDown, stringResource(R.string.player_hide_queue), tint = c.text2, modifier = Modifier.size(22.dp))
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 24.dp),
        ) {
            itemsIndexed(upcoming, key = { _, item -> item.id }) { index, item ->
                val isDragging = draggingId == item.id
                UpNextRow(
                    item = item,
                    modifier = if (isDragging) Modifier else Modifier.animateItem(),
                    isDragging = isDragging,
                    dragDy = if (isDragging) dragDy else 0f,
                    onMeasured = { h -> rowHeightPx = h },
                    onPlay = { onPlay(item.id) },
                    onRemove = { onRemove(item.id) },
                    onDragStart = { draggingId = item.id; dragDy = 0f; haptics.heavy() },
                    onDrag = { dy -> dragDy += dy },
                    onDragEnd = {
                        val h = if (rowHeightPx > 0f) rowHeightPx else 1f
                        val steps = (dragDy / h).roundToInt()
                        val to = (index + steps).coerceIn(0, upcoming.lastIndex)
                        if (to != index) { onMove(baseIndex + index, baseIndex + to); haptics.select() }
                        draggingId = null; dragDy = 0f
                    },
                    onDragCancel = { draggingId = null; dragDy = 0f },
                )
            }
        }
    }
}

@Composable
private fun UpNextRow(
    item: fm.rizx.player.domain.model.QueueItem,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    dragDy: Float = 0f,
    onMeasured: (Float) -> Unit = {},
    onPlay: () -> Unit = {},
    onRemove: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val c = RizxTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.height.toFloat()) }
            // While lifted: raise it above its neighbours, follow the finger, and give it a solid card back
            // with a hard shadow so it reads as picked up.
            .then(
                if (isDragging) {
                    Modifier.zIndex(1f).graphicsLayer { translationY = dragDy }.brutalShadow(c.shadowHard, offset = 4.dp).background(c.elev)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tap-to-play region (cover + text), kept separate from the remove/drag controls so the three
        // touch targets never fight for the same gesture.
        Row(
            Modifier.weight(1f).clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            CoverArt(
                tintFor(item.track.source.id), initial = null, Modifier.size(46.dp),
                imageUrl = item.track.artwork.coverUrl(),
            )
            Column(Modifier.weight(1f)) {
                Text(item.track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                    style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Remove from the queue.
        Box(
            Modifier.size(34.dp).clickableScale(scale = 0.9f, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RizxIcons.Close, stringResource(R.string.player_remove_from_queue), tint = c.muted, modifier = Modifier.size(17.dp))
        }
        // Drag handle — long-press, then drag to reorder. Its own pointer node so a normal list scroll and
        // the tap-to-play above are untouched.
        Box(
            Modifier
                .size(34.dp)
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(RizxIcons.Grip, stringResource(R.string.player_reorder), tint = if (isDragging) c.text else c.muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun GlassButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    isDark: Boolean,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    tint: Color? = null,
) {
    val c = RizxTheme.colors
    // Light theme used to get a fully transparent background *and* border, leaving bare glyphs floating with
    // nothing to say they were tappable. Both themes now get a container.
    val bg = if (isDark) Color(0xFF0A0A0D).copy(alpha = 0.34f) else c.inset
    val line = if (isDark) Color.White.copy(alpha = 0.16f) else c.hardLine
    val iconTint = tint ?: if (isDark) Color.White.copy(alpha = 0.85f) else c.text
    RizxIconButton(icon, contentDescription, onClick, size = size, iconSize = iconSize, tint = iconTint, background = bg, border = line)
}


/**
 * The double-tap-to-like flourish: a red heart pops over the cover and fades. Driven by a change [trigger]
 * (incremented on each double-tap) so it replays without ever becoming a continuous animation — the two
 * `Animatable`s run once per trigger and rest. Renders nothing until the first tap.
 */
@Composable
private fun BoxScope.LikeStamp(trigger: Int) {
    if (trigger == 0) return
    val c = RizxTheme.colors
    val pop = remember { Animatable(0.5f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        pop.snapTo(0.5f); fade.snapTo(0f)
        launch { pop.animateTo(1.1f, tween(300, easing = FastOutSlowInEasing)) }
        fade.animateTo(1f, tween(110))
        kotlinx.coroutines.delay(200)
        fade.animateTo(0f, tween(240))
    }
    Icon(
        RizxIcons.Favorite,
        contentDescription = null,
        tint = c.redAccent,
        modifier = Modifier
            .align(Alignment.Center)
            .size(96.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = fade.value },
    )
}

/** The scrub time chip: a hard-cornered dot-matrix readout with a red tick, floated over the playhead. */
@Composable
private fun ScrubBubble(timeText: String, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Row(
        modifier
            .background(c.elev)
            .border(1.dp, c.hardLine)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).background(c.redAccent))
        Text(timeText, style = dot(13, FontWeight.Black), color = c.text, maxLines = 1)
    }
}

// (The animated "snake-lights" aurora was removed: the dark Now Playing backdrop is now a
// smooth wash of the album's own colours, matching the reference.)

/**
 * Height of the waveform scrubber (was 52.dp). Trimmed ~25% to hand vertical space back to the title and
 * transport below it, which is what keeps the player from cramping on shorter screens. The bars scale to
 * this box and the seek maths is relative to the canvas, so shrinking it costs no scrubbing accuracy — and
 * it stays well above the 48.dp touch-target guidance once the 14.dp vertical padding is counted.
 */
private val WAVEFORM_HEIGHT = 39.dp

/**
 * The geometry the two action rows share — track actions (add-to-playlist · like) and the bottom bar
 * (nearby devices · up-next peek · radio).
 *
 * They are constants rather than repeated literals because their whole job is to be *identical*: same
 * inset and same button size is what puts the four buttons on two vertical axes. Change one and the
 * rows drift apart again, which is exactly the misalignment this replaced.
 */
private val ACTION_INSET = 24.dp

/** The transport row's own, tighter inset — see the row for why it cannot share [ACTION_INSET]. */
private val TRANSPORT_INSET = 18.dp

/**
 * How wide the control stack is allowed to get in the side-by-side layout. Roughly a comfortable phone's
 * width, which is what these rows were designed against — stretched to a tablet's full half they read as
 * five buttons pinned to two distant edges.
 */
private val PANE_CONTENT_MAX = 460.dp
private val ACTION_BUTTON = 46.dp
private val ACTION_ICON = 22.dp

/**
 * How much of the screen the up-next drawer covers when open. Trimmed twice at the owner's request
 * (0.62 → 0.465 → 0.38): four upcoming songs at a glance, and most of the player — artwork, waveform,
 * transport — still visible behind it, which is what makes it read as a peek rather than a screen change.
 */
private const val QUEUE_DRAWER_FRACTION = 0.38f

/**
 * Vertical space everything below the artwork needs at a 1.0 font scale, with headroom.
 *
 * Measured from the layout rather than estimated: waveform 67 + times 22 + title block 81 + transport 86
 * + track actions 54 = 310, then the bottom action bar 58 (nearby-devices · up-next peek · radio).
 *
 * Down 69dp from 454: that was the artist/album readout below the up-next bar, now gone. It repeated the
 * artist already printed under the title and pushed the artwork 69dp shorter for the privilege — removing
 * it is the whole of "organize the player better", and the artwork simply gets the space back.
 */
private val CONTROLS_RESERVE = 385.dp
