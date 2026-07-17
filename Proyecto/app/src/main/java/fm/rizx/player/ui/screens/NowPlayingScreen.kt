package fm.rizx.player.ui.screens

import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.PlaybackQueue
import fm.rizx.player.core.formatDuration
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.animateFloatAsState
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fm.rizx.player.R
import fm.rizx.player.core.formatClock
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.PlayerDotTrail
import fm.rizx.player.ui.components.PulsingPlayButton
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.VerticalLabel
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.dot
import fm.rizx.player.ui.theme.dotGrid
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.rememberRizxHaptics
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

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
    /** Opens the artist's page. Null when the track's artist carries no `ProviderRef` to open. */
    onOpenArtist: (() -> Unit)? = null,
    album: String = "",
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
    /** Jump to a queue item (by its per-insertion id) — makes it the current song. */
    onPlayQueueItem: (String) -> Unit = {},
    // True while the current track is resolving/buffering to play — shows a loader on the play button.
    loading: Boolean = false,
    // Live audio spectrum (0..1 per bar) read lazily inside the waveform's draw so only it invalidates.
    levels: () -> FloatArray = { FloatArray(0) },
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
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
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- Album art ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clipToBounds(),
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
                if (artworkUrl != null) {
                    coil.compose.AsyncImage(
                        model = artworkUrl,
                        contentDescription = "Album artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.velvet_asphalt),
                        contentDescription = "Album artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // The canvas: the song's own video, muted and looping, fading in over the artwork once it
                // is actually playing — so a track with no video, or a slow extraction, simply never
                // reveals anything and the cover stays put.
                if (canvasVideo != null) {
                    val fade by animateFloatAsState(
                        targetValue = if (canvasPlaying) 1f else 0f,
                        animationSpec = tween(600),
                        label = "canvasFade",
                    )
                    if (fade > 0f) {
                        AndroidView(
                            factory = { ctx -> TextureView(ctx).also(canvasVideo) },
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade },
                        )
                    }
                }
                // Bottom scrim — fades the cover into the zone below (paper in light, the wash in dark).
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.6f to Color.Transparent,
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
                    RizxIconButton(RizxIcons.Back, "Back", onBack, background = heroBtnBg, border = heroBtnLine, tint = if (c.isDark) Color.White else c.heroText)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RizxIconButton(RizxIcons.Lyrics, "Lyrics", onOpenLyrics, background = heroBtnBg, border = heroBtnLine, tint = if (c.isDark) Color.White else c.heroText)
                        Box {
                            RizxIconButton(
                                RizxIcons.MoreVert, "More options", { menuOpen = true },
                                background = heroBtnBg, border = heroBtnLine,
                                tint = if (c.isDark) Color.White else c.heroText,
                            )
                            menu(menuOpen) { menuOpen = false }
                        }
                    }
                }
            }

            // ---- Controls zone ----
            // Dark: a smooth wash of the album's colours (purple -> coral -> dark) flowing down
            // from the cover. Light/Paper: nothing — the plain paper background, like the reference.
            Box(Modifier.fillMaxWidth().weight(1f)) {
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
                Column(Modifier.fillMaxSize()) {
                    // ---- Waveform scrubber (tap or drag to seek) ----
                    // Local drag override so the playhead follows the finger instantly, before the polled
                    // position round-trips back through the player (same trick as the mini-player). Read
                    // only in the draw phase below, so a drag redraws just the waveform, not the screen.
                    var drag by remember { mutableStateOf<Float?>(null) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .height(52.dp)
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
                    }

                    // ---- Time row: elapsed · "N OF M" · remaining (dot-matrix numerals) ----
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp),
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

                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            title,
                            style = sg(26, FontWeight.Bold, -0.02f).copy(shadow = npTextShadow),
                            color = c.text,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            // Marquee: a title too long for one line scrolls leftward instead of clipping.
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        )
                        Text(
                            artist,
                            style = mr(14, FontWeight.Medium).copy(shadow = npTextShadow),
                            color = c.text2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            // Tappable only when we actually know which artist this is: a track's
                            // ArtistCredit carries no ProviderRef unless its metadata provider gave one
                            // (Deezer does, YouTube doesn't), and a name alone is not an identity.
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .then(
                                    if (onOpenArtist != null) {
                                        Modifier.clickableScale(scale = 0.96f, onClick = onOpenArtist)
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    // ---- Controls ----
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        GlassButton(RizxIcons.PlaylistAdd, "Add to playlist", onAddToPlaylist, c.isDark, size = 48.dp, iconSize = 24.dp)
                        GlassButton(RizxIcons.SkipPrevious, "Previous", onPrevious, c.isDark, size = 50.dp, iconSize = 32.dp)
                        PulsingPlayButton(
                            isPlaying = isPlaying,
                            onClick = onTogglePlay,
                            size = 74.dp,
                            iconSize = 40.dp,
                            fillColor = npAccent,
                            onFillColor = npOnFill,
                            glowColor = npAccent,
                            // No animated blur halo: a continuously-animated blur is very expensive in
                            // software rendering (emulator/low-end) and starves audio. Keep the button flat.
                            glow = false,
                            loading = loading,
                        )
                        GlassButton(RizxIcons.SkipNext, "Next", onNext, c.isDark, size = 50.dp, iconSize = 32.dp)
                        GlassButton(
                            if (liked) RizxIcons.Favorite else RizxIcons.FavoriteBorder,
                            "Like",
                            onToggleLike,
                            c.isDark,
                            size = 48.dp,
                            iconSize = 24.dp,
                            tint = if (liked) c.redAccent else null,
                        )
                    }

                    Spacer(Modifier.weight(1f))
                }
                // Rotated technical label running up the left edge (refs #3/#4).
                VerticalLabel(
                    "NOW PLAYING",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    color = c.muted,
                )
            }

            // ---- Up-next handle: pull up (or tap) to reveal the queue drawer ----
            if (upcoming.isNotEmpty()) {
                UpNextHandle(count = upcoming.size, onOpen = { queueOpen = true })
            }

            // ---- Footer ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (c.isDark) Color(0xFF0C0C10).copy(alpha = 0.6f) else c.elev)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FooterChip(
                    // Repeat-one gets its own glyph; the accent fill is already spent saying "on".
                    icon = if (repeatMode == RepeatMode.ONE) RizxIcons.RepeatOne else RizxIcons.Repeat,
                    contentDescription = when (repeatMode) {
                        RepeatMode.OFF -> "Repeat off — tap to repeat the queue"
                        RepeatMode.ALL -> "Repeating the queue — tap to repeat this song"
                        RepeatMode.ONE -> "Repeating this song — tap to turn repeat off"
                    },
                    isDark = c.isDark,
                    active = repeatMode != RepeatMode.OFF,
                    accent = c.redAccent,
                    onClick = onToggleRepeat,
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(artist, style = mr(13, FontWeight.Bold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (album.isNotBlank()) {
                        Text(
                            album,
                            style = mr(11, FontWeight.Medium),
                            color = c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                FooterChip(
                    RizxIcons.Shuffle,
                    if (shuffleOn) "Shuffle on — tap to play in order" else "Shuffle off — tap to shuffle",
                    c.isDark,
                    active = shuffleOn,
                    accent = c.redAccent,
                    onClick = onToggleShuffle,
                )
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
                onPlay = { id -> queueOpen = false; onPlayQueueItem(id) },
                onCollapse = { queueOpen = false },
            )
        }
    }
}

/** A slim pull-up handle above the footer: the drawer's peek. Tap or swipe up to open. */
@Composable
private fun UpNextHandle(count: Int, onOpen: () -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (c.isDark) Color(0xFF0C0C10).copy(alpha = 0.6f) else c.elev)
            // Tap and swipe live in separate pointerInput nodes so they don't fight — a drag detector on
            // the same node as `clickable` swallows the tap.
            .pointerInput(Unit) { detectTapGestures { onOpen() } }
            .pointerInput(Unit) { detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -6f) onOpen() } }
            .padding(top = 8.dp, bottom = 2.dp),
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
            CodeLabel("UP NEXT · $count", size = 11)
        }
    }
}

/** The drawer body: a drag-down handle, a header, and the upcoming songs — tappable to jump. */
@Composable
private fun UpNextPanel(
    upcoming: List<fm.rizx.player.domain.model.QueueItem>,
    onPlay: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f)
            .background(c.elev)
            .border(1.dp, c.hardLine),
    ) {
        // Drag-down / tap handle to hide the drawer and return to the player.
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { onCollapse() } }
                .pointerInput(Unit) { detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 6f) onCollapse() } }
                .padding(top = 10.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(36.dp).height(4.dp).background(c.muted.copy(alpha = 0.6f)))
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Up next", style = sg(20, FontWeight.Bold, -0.01f), color = c.text, modifier = Modifier.weight(1f))
                Icon(RizxIcons.ChevronDown, "Hide queue", tint = c.text2, modifier = Modifier.size(22.dp))
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 24.dp),
        ) {
            itemsIndexed(upcoming, key = { _, item -> item.id }) { _, item ->
                UpNextRow(item, onPlay = { onPlay(item.id) })
            }
        }
    }
}

@Composable
private fun UpNextRow(item: fm.rizx.player.domain.model.QueueItem, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay)
            .padding(vertical = 8.dp),
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
                item.track.artists.joinToString { it.name }.ifEmpty { "Unknown artist" },
                style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatDuration(item.track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
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
    val bg = if (isDark) Color(0xFF0A0A0D).copy(alpha = 0.34f) else Color.Transparent
    val line = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Transparent
    val iconTint = tint ?: if (isDark) Color.White.copy(alpha = 0.85f) else c.text
    RizxIconButton(icon, contentDescription, onClick, size = size, iconSize = iconSize, tint = iconTint, background = bg, border = line)
}

@Composable
private fun FooterChip(
    icon: ImageVector,
    contentDescription: String?,
    isDark: Boolean,
    active: Boolean = false,
    accent: Color = RizxTheme.colors.accent,
    onClick: () -> Unit = {},
) {
    val c = RizxTheme.colors
    val bg = when {
        active -> accent
        isDark -> Color.White.copy(alpha = 0.09f)
        else -> c.inset
    }
    val tint = when {
        active -> c.onFill
        isDark -> Color.White.copy(alpha = 0.85f)
        else -> c.text2
    }
    val line = if (isDark) Color.White.copy(alpha = 0.20f) else c.hardLine
    RizxIconButton(
        icon, contentDescription, onClick,
        size = 46.dp, iconSize = 22.dp, tint = tint,
        background = bg, border = if (active) Color.Transparent else line,
    )
}

// (The animated "snake-lights" aurora was removed: the dark Now Playing backdrop is now a
// smooth wash of the album's own colours, matching the reference.)
