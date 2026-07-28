package fm.rizx.player.ui.lyrics

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.lyrics.LyricsTimeline
import fm.rizx.player.domain.lyrics.activeIndexAt
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.ui.util.rememberRizxHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Where the active line is parked: a little above the middle, so what comes next is already readable. */
private const val SCROLL_ANCHOR = 0.40f

/** Leave the line alone while it sits in this band — re-animating a line that is already there is jitter. */
private val SETTLED_BAND = 0.30f..0.55f

/** How long after a finger lifts before the screen takes the scroll back. */
private const val RESUME_AFTER_SCROLL_MS = 3_000L

/**
 * The karaoke view: timed lines, the active one filling in as it is sung.
 *
 * The per-frame work is deliberately tiny and deliberately narrow. [LyricsTimeline] is built once per
 * lyric; the clock only ticks while a timed lyric is on screen and playing; and the only thing that
 * happens sixty times a second is one line redrawing itself. Composition runs when the active line
 * changes — a few times a minute — because `derivedStateOf` filters the clock down to an index.
 *
 * Tapping a line seeks to it, which is the gesture that makes timed lyrics worth having.
 */
@Composable
fun KaraokeLyricsList(
    lines: List<LyricLine>,
    offsetMs: Long,
    /** A flow, not a value: see [rememberLyricsClock] — playback must never recompose this list. */
    playback: StateFlow<PlaybackState>,
    quality: LyricsVisualQuality,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptics = rememberRizxHaptics()

    val timeline = remember(lines) { LyricsTimeline.of(lines) }
    val profile = rememberLyricsRenderProfile(quality)
    val clock = rememberLyricsClock(playback, enabled = lines.isNotEmpty(), hz = profile.hz)

    // The clock changes every frame; this changes when the *line* changes. Reading the derived value in
    // composition therefore costs a recomposition a few times a minute, not sixty times a second.
    val activeIndex by remember(lines, offsetMs, clock) {
        derivedStateOf { lines.activeIndexAt(clock.frameMs, offsetMs) }
    }

    // Auto-scroll pauses while a finger is on the list, and resumes a few seconds after it lifts —
    // otherwise reading ahead turns into a tug of war with the animation.
    var dragging by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { dragging = true; paused = true }
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
            }
        }
    }
    LaunchedEffect(dragging) {
        if (!dragging && paused) {
            delay(RESUME_AFTER_SCROLL_MS)
            paused = false
        }
    }
    LaunchedEffect(activeIndex, paused) {
        if (activeIndex < 0 || paused) return@LaunchedEffect
        val info = listState.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        if (viewport <= 0) return@LaunchedEffect
        // Already roughly where it belongs? Then scrolling would only shuffle the text under the reader's
        // eyes. Short lines often leave two consecutive lines inside the band, and animating between them
        // is exactly the jitter this avoids.
        val onScreen = info.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        if (onScreen != null) {
            val centre = (onScreen.offset + onScreen.size / 2f - info.viewportStartOffset) / viewport
            if (centre in SETTLED_BAND) return@LaunchedEffect
        }
        listState.animateScrollToItem(activeIndex, -(viewport * SCROLL_ANCHOR).toInt())
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
        ) {
            itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                KaraokeLyricsLine(
                    line = line,
                    index = index,
                    active = index == activeIndex,
                    profile = profile,
                    timeline = timeline,
                    clock = clock,
                    offsetMs = offsetMs,
                    onClick = {
                        haptics.select()
                        // The inverse of the offset the timeline subtracts: lyric time back to audio time.
                        onSeekMs((line.timeMs + offsetMs).coerceAtLeast(0L))
                    },
                )
            }
        }

        // Scrolling by hand hands control over; this hands it back without waiting out the timer.
        if (paused) {
            RizxChip(
                label = stringResource(R.string.lyrics_resume_autoscroll),
                active = false,
                onClick = { paused = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}
