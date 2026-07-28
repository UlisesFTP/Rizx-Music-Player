package fm.rizx.player.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.core.formatClock
import fm.rizx.player.data.lyrics.activeIndexAt
import fm.rizx.player.data.lyrics.sungWordCountAt
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.PulsingPlayButton
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.player.LyricsContent
import fm.rizx.player.ui.player.LyricsSearchState
import fm.rizx.player.ui.player.LyricsUiState
import fm.rizx.player.ui.player.LyricsViewModel
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.dot
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.rememberRizxHaptics
import kotlinx.coroutines.delay

/**
 * Lyrics for whatever is playing — timed and karaoke-style when the source has timings, plain prose
 * otherwise.
 *
 * The transport lives at the bottom on purpose: reading along and needing to pause used to mean leaving
 * the screen, which is exactly when you don't want to.
 *
 * [positionMs] is a **lambda, not a value**, so the 4 Hz position ticker doesn't recompose the screen —
 * only the derived line index does, and that changes a few times a minute. Same trick the Now Playing
 * waveform uses for the audio spectrum.
 */
@Composable
fun LyricsScreen(
    onBack: () -> Unit,
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekMs: (Long) -> Unit,
    vm: LyricsViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            LyricsHeader(
                state = state,
                onBack = onBack,
                onSearch = vm::openSearch,
                onToggleSynced = vm::toggleSyncedMode,
            )

            Box(Modifier.fillMaxWidth().weight(1f)) {
                Crossfade(
                    targetState = state.content to state.showSynced,
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                    label = "lyricsBody",
                ) { (content, synced) ->
                    when (content) {
                        LyricsContent.NoTrack -> Centered(stringResource(R.string.lyrics_no_track))
                        LyricsContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DotMatrixSpinner(color = c.accent, diameter = 34.dp)
                        }
                        LyricsContent.Offline -> Centered(stringResource(R.string.lyrics_offline))
                        is LyricsContent.Empty -> NoLyrics(content.title, onSearch = vm::openSearch)
                        is LyricsContent.Error -> Centered(content.message)
                        is LyricsContent.Ready -> when {
                            content.lyrics.instrumental && content.lyrics.isEmpty -> Centered(stringResource(R.string.lyrics_instrumental))
                            synced -> SyncedLyrics(
                                lines = content.lyrics.lines,
                                offsetMs = content.offsetMs,
                                positionMs = positionMs,
                                onSeekMs = onSeekMs,
                            )
                            else -> PlainLyrics(content.lyrics.plain ?: content.lyrics.lines.joinToString("\n") { it.text })
                        }
                    }
                }
            }

            // Only meaningful once there are timings on screen to shift.
            if (state.showSynced) {
                val ready = state.content as? LyricsContent.Ready
                OffsetStrip(
                    offsetMs = ready?.offsetMs ?: 0L,
                    pinned = ready?.pinned == true,
                    onNudge = vm::nudgeOffset,
                    onReset = vm::resetOffset,
                    onResetToAutomatic = vm::resetToAutomatic,
                )
            }

            LyricsTransport(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
                onSeekMs = onSeekMs,
            )
        }

        LyricsSearchOverlay(
            state = state.search,
            defaultQuery = vm.defaultQuery(),
            onSearch = vm::search,
            onPick = vm::applyCandidate,
            onDismiss = vm::closeSearch,
        )
    }
}

// ---- Header ----

@Composable
private fun LyricsHeader(
    state: LyricsUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onToggleSynced: () -> Unit,
) {
    val c = RizxTheme.colors
    val ready = state.content as? LyricsContent.Ready
    val hasTimings = ready?.lyrics?.isSynced == true
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RizxIconButton(RizxIcons.Back, stringResource(R.string.lyrics_back_cd), onBack, size = 44.dp, iconSize = 24.dp, tint = c.text)
        if (state.title.isNotBlank()) {
            CoverArt(
                tintFor(state.title), initial = null, Modifier.size(40.dp),
                imageUrl = state.artworkUrl,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.title.ifBlank { stringResource(R.string.lyrics_title_fallback) },
                style = sg(17, FontWeight.Bold, -0.01f),
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sourceLine(
                    state,
                    chosenLabel = stringResource(R.string.lyrics_source_chosen),
                    viaTemplate = stringResource(R.string.lyrics_source_via),
                    notSyncedLabel = stringResource(R.string.lyrics_source_not_synced),
                ),
                style = mr(11, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RizxIconButton(
            RizxIcons.Search, stringResource(R.string.lyrics_search_other_cd), onSearch,
            size = 44.dp, iconSize = 21.dp, tint = c.text,
        )
        // The mode switch shows its state by filling, like the shuffle/repeat buttons in the player.
        if (hasTimings) {
            RizxIconButton(
                RizxIcons.Lyrics,
                if (state.syncedMode) stringResource(R.string.lyrics_synced_on_cd) else stringResource(R.string.lyrics_synced_off_cd),
                onToggleSynced,
                size = 44.dp,
                iconSize = 21.dp,
                tint = if (state.syncedMode) c.onRed else c.text2,
                background = if (state.syncedMode) c.redAccent else c.inset,
                border = if (state.syncedMode) Color.Transparent else c.hardLine,
            )
        }
    }
}

/** The byline under the title: who wrote these words down, and whether the user chose them. */
private fun sourceLine(
    state: LyricsUiState,
    chosenLabel: String,
    viaTemplate: String,
    notSyncedLabel: String,
): String {
    val ready = state.content as? LyricsContent.Ready ?: return state.artist
    val bits = buildList {
        add(state.artist)
        if (ready.pinned) add(chosenLabel) else ready.lyrics.sourceName.takeIf { it.isNotBlank() }?.let { add(viaTemplate.format(it)) }
        if (!ready.lyrics.isSynced) add(notSyncedLabel)
    }
    return bits.filter { it.isNotBlank() }.joinToString(" · ")
}

// ---- Bodies ----

/**
 * The karaoke view. The active line is bold and fully opaque while the rest recede — animated, so a line
 * change reads as a transition instead of a jump.
 *
 * Tapping a line seeks to it, which is the gesture that makes timed lyrics worth having.
 */
@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    offsetMs: Long,
    positionMs: () -> Long,
    onSeekMs: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val haptics = rememberRizxHaptics()
    // derivedStateOf: `positionMs()` is read here and nowhere else, so the 4 Hz ticker only invalidates
    // when it actually crosses into a new line.
    val activeIndex by remember(lines, offsetMs) {
        derivedStateOf { lines.activeIndexAt(positionMs(), offsetMs) }
    }

    // Karaoke needs a finer clock than the 4 Hz transport ticker — at 250 ms the highlight visibly jumps
    // whole words. This one runs only while a word-timed lyric is on screen, and only for the active
    // line, so it is a cheap text re-measure rather than a continuous animation.
    val wordTimed = remember(lines) { lines.any { it.words.isNotEmpty() } }
    var wordClockMs by remember { mutableStateOf(0L) }
    LaunchedEffect(wordTimed, activeIndex) {
        if (!wordTimed || activeIndex < 0) return@LaunchedEffect
        while (true) {
            wordClockMs = positionMs()
            delay(WORD_TICK_MS)
        }
    }
    val sungWords = remember(lines, offsetMs, activeIndex, wordClockMs) {
        lines.getOrNull(activeIndex)?.sungWordCountAt(wordClockMs, offsetMs) ?: 0
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
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        // Park the active line a third of the way down, so the words coming next are already visible.
        listState.animateScrollToItem(activeIndex, -viewport / 3)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
            LyricRow(
                line = line,
                active = index == activeIndex,
                sungWords = if (index == activeIndex) sungWords else 0,
                onClick = {
                    haptics.select()
                    onSeekMs((line.timeMs + offsetMs).coerceAtLeast(0L))
                },
            )
        }
    }
}

/**
 * One line. When the line is the active one **and** carries word timings, [sungWords] says how many of
 * its words have already been sung, and the row is drawn in two pieces: what's been sung in full
 * strength, the rest dimmed — the line fills in as it is performed.
 */
@Composable
private fun LyricRow(line: LyricLine, active: Boolean, sungWords: Int, onClick: () -> Unit) {
    val c = RizxTheme.colors
    val spec = tween<Float>(300, easing = FastOutSlowInEasing)
    val alpha by animateFloatAsState(if (active) 1f else 0.38f, spec, label = "lyricAlpha")
    val scale by animateFloatAsState(if (active) 1f else 0.965f, spec, label = "lyricScale")
    val color by animateColorAsState(
        if (active) c.text else c.text2,
        tween(300, easing = FastOutSlowInEasing),
        label = "lyricColor",
    )

    val content = Modifier
        .fillMaxWidth()
        .clickableScale(scale = 0.985f, onClick = onClick)
        .padding(vertical = 9.dp)
        .graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0.5f)
        }

    if (line.text.isBlank()) {
        // An instrumental gap. Marking it keeps the previous line from staying lit through a solo.
        Box(content, contentAlignment = Alignment.CenterStart) {
            CodeLabel("· · ·", color = color, size = 13)
        }
    } else {
        val style = sg(22, if (active) FontWeight.Bold else FontWeight.Medium, -0.01f, lineHeight = 30)
        if (active && line.words.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    val sung = line.words.take(sungWords).joinToString(separator = "") { it.text }
                    val rest = line.words.drop(sungWords).joinToString(separator = "") { it.text }
                    withStyle(SpanStyle(color = c.text)) { append(sung) }
                    // Not yet sung: the same words, held back — the split is what reads as karaoke.
                    withStyle(SpanStyle(color = c.text2)) { append(rest) }
                },
                style = style,
                modifier = content,
            )
        } else {
            Text(line.text, style = style, color = color, modifier = content)
        }
    }
}

@Composable
private fun PlainLyrics(text: String) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(text, style = mr(15, FontWeight.Medium, lineHeight = 26), color = c.text2)
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun NoLyrics(title: String, onSearch: () -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.lyrics_no_results_for, title),
            style = mr(14, FontWeight.Medium),
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        // The automatic match keys on artist + title; when those are off (a "… [Official Video]" upload,
        // say) searching by hand is the only way through, so offer it right where the dead end is.
        Box(
            Modifier
                .border(1.dp, c.hardLine)
                .background(c.elev)
                .clickableScale(scale = 0.95f, onClick = onSearch)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            CodeLabel(stringResource(R.string.lyrics_search_by_hand), color = c.text, size = 11)
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = mr(14, FontWeight.Medium), color = RizxTheme.colors.muted, textAlign = TextAlign.Center)
    }
}

// ---- Timing correction ----

/**
 * Nudges the words against the audio. It earns its place because our audio usually isn't the recording
 * the lyric was timed against — a YouTube upload with a longer intro drifts by seconds, and without this
 * the only remedy would be discarding an otherwise correct lyric.
 */
@Composable
private fun OffsetStrip(
    offsetMs: Long,
    pinned: Boolean,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
    onResetToAutomatic: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.elev)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NudgeButton("−0.5s", stringResource(R.string.lyrics_nudge_earlier_cd)) { onNudge(-OFFSET_STEP_MS) }
        Box(
            Modifier
                .clickableScale(scale = 0.94f, onClick = if (pinned) onResetToAutomatic else onReset)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                if (offsetMs == 0L && !pinned) stringResource(R.string.lyrics_in_sync).uppercase() else formatOffset(offsetMs),
                style = dot(12, FontWeight.Bold),
                color = if (offsetMs == 0L) c.muted else c.redAccent,
            )
        }
        NudgeButton("+0.5s", stringResource(R.string.lyrics_nudge_later_cd)) { onNudge(OFFSET_STEP_MS) }
        Spacer(Modifier.weight(1f))
        CodeLabel(if (pinned) stringResource(R.string.lyrics_tap_to_unpin) else stringResource(R.string.lyrics_timing_label), size = 10)
    }
}

@Composable
private fun NudgeButton(label: String, description: String, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Box(
        Modifier
            .semantics { contentDescription = description }
            .border(1.dp, c.hardLine)
            .background(c.inset)
            .clickableScale(scale = 0.94f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CodeLabel(label, color = c.text, size = 11)
    }
}

/** Signed, in seconds — "+1.5s" reads faster than a millisecond count. */
private fun formatOffset(offsetMs: Long): String {
    val seconds = offsetMs / 1000.0
    val sign = if (offsetMs > 0) "+" else if (offsetMs < 0) "−" else ""
    return "$sign${String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(seconds))}S"
}

// ---- Transport ----

/** Compact play controls so reading along never means leaving the screen to pause. */
@Composable
private fun LyricsTransport(
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekMs: (Long) -> Unit,
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (c.isDark) Color(0xFF0C0C10) else c.elev)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val elapsed by remember { derivedStateOf { positionMs() } }
            Text(formatClock(elapsed / 1000.0), style = dot(12, FontWeight.Black), color = c.text)
            Box(
                Modifier
                    .weight(1f)
                    .height(20.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            if (durationMs > 0L) {
                                haptics.select()
                                onSeekMs(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(c.waveTrack))
                val fraction = if (durationMs > 0L) (elapsed.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(c.redAccent)
                        .align(Alignment.CenterStart),
                )
            }
            Text(
                formatClock(((durationMs - elapsed).coerceAtLeast(0L)) / 1000.0),
                style = dot(12, FontWeight.Black),
                color = c.text2,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            RizxIconButton(RizxIcons.SkipPrevious, stringResource(R.string.lyrics_previous_cd), onPrevious, size = 48.dp, iconSize = 28.dp, tint = c.text)
            Spacer(Modifier.size(14.dp))
            PulsingPlayButton(
                isPlaying = isPlaying,
                onClick = onTogglePlay,
                size = 56.dp,
                iconSize = 30.dp,
                glow = false,
            )
            Spacer(Modifier.size(14.dp))
            RizxIconButton(RizxIcons.SkipNext, stringResource(R.string.lyrics_next_cd), onNext, size = 48.dp, iconSize = 28.dp, tint = c.text)
        }
    }
}

// ---- Manual picker ----

/**
 * The escape hatch for a wrong match.
 *
 * Automatic matching keys on artist + title, which fails predictably: a YouTube upload titled
 * "… (Official Video) [HD]", a remix credited to the remixer, a live take. Searching by hand fixes all of
 * them, and the pick is remembered for the song afterwards.
 *
 * A full-height panel rather than a dialog because results are a list — and because every row already
 * carries its lyrics, choosing one applies instantly with no second request.
 */
@Composable
private fun LyricsSearchOverlay(
    state: LyricsSearchState,
    defaultQuery: String,
    onSearch: (String) -> Unit,
    onPick: (fm.rizx.player.domain.model.LyricsCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == LyricsSearchState.Closed) return
    val c = RizxTheme.colors
    var query by remember { mutableStateOf(defaultQuery) }

    // Run the obvious search on open: nine times out of ten the song we're playing is the right query.
    LaunchedEffect(Unit) { if (defaultQuery.isNotBlank()) onSearch(defaultQuery) }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg.copy(alpha = 0.75f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .background(c.elev)
            .border(1.dp, c.hardLine)
            // Swallow taps so they don't reach the dismiss scrim behind the panel.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lyrics_find_title), style = sg(20, FontWeight.Bold, -0.01f), color = c.text, modifier = Modifier.weight(1f))
            RizxIconButton(RizxIcons.Close, stringResource(R.string.action_close), onDismiss, size = 40.dp, iconSize = 20.dp, tint = c.text)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            shape = RectangleShape,
            placeholder = { Text(stringResource(R.string.lyrics_search_placeholder), style = mr(13, FontWeight.Medium)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Box(
            Modifier
                .padding(top = 10.dp)
                .background(c.fill)
                .clickableScale(scale = 0.96f, onClick = { onSearch(query) })
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            CodeLabel(stringResource(R.string.action_search), color = c.onFill, size = 11)
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
            when (state) {
                LyricsSearchState.Closed, LyricsSearchState.Idle -> Unit
                LyricsSearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DotMatrixSpinner(color = c.accent, diameter = 30.dp)
                }
                LyricsSearchState.NoResults -> Centered(stringResource(R.string.lyrics_search_no_results))
                is LyricsSearchState.Error -> Centered(state.message)
                is LyricsSearchState.Results -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(state.items, key = { _, item -> item.id }) { _, item ->
                        CandidateRow(item, onPick = { onPick(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: fm.rizx.player.domain.model.LyricsCandidate,
    onPick: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                candidate.title.ifBlank { stringResource(R.string.lyrics_untitled) },
                style = mr(14, FontWeight.SemiBold),
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    candidate.artist.takeIf { it.isNotBlank() },
                    candidate.album?.takeIf { it.isNotBlank() },
                    candidate.durationMs?.let { formatClock(it / 1000.0) },
                ).joinToString(" · "),
                style = mr(12, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The badge is the deciding factor between two otherwise identical rows — and a word-timed
        // transcription beats a line-timed one, so it says so.
        if (candidate.lyrics.isSynced) {
            val badge =
                if (candidate.lyrics.isWordSynced) R.string.lyrics_word_badge else R.string.lyrics_synced_badge
            Box(Modifier.background(c.redAccent).padding(horizontal = 7.dp, vertical = 3.dp)) {
                CodeLabel(stringResource(badge), color = c.onRed, size = 9)
            }
        }
    }
}

/** Half a second: the smallest step that is actually perceptible against a sung line. */
private const val OFFSET_STEP_MS = 500L

/** Grace period before auto-scroll takes the list back from the reader. */
private const val RESUME_AFTER_SCROLL_MS = 3_000L

/**
 * How often the karaoke highlight re-reads the playback position (~12 Hz). Fine enough that words light
 * up on the beat, coarse enough that it stays a periodic text re-measure — deliberately nothing like a
 * per-frame animation, which this app keeps away from the playing screen.
 */
private const val WORD_TICK_MS = 80L
