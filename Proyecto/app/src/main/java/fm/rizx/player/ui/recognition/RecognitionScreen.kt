package fm.rizx.player.ui.recognition

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import fm.rizx.player.R
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionHistoryItem
import fm.rizx.player.domain.recognition.RecognitionState
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.Eyebrow
import fm.rizx.player.ui.components.RizxActionButton
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.SectionHeader
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.rememberRizxHaptics
import kotlin.math.roundToInt

/**
 * Identify whatever is playing in the room.
 *
 * The screen is a rendering of one state machine and nothing more — it holds no session of its own, so
 * rotating the phone or stepping out to the permission settings and back rejoins a capture already in
 * progress instead of restarting it.
 *
 * The microphone permission is handled here rather than in the domain, with the same three-way pattern
 * the local library uses: ask on demand, detect a permanent refusal through
 * `shouldShowRequestPermissionRationale`, and re-check on resume so returning from Settings works
 * without a restart.
 */
@Composable
fun RecognitionScreen(
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    vm: RecognitionViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasMicrophonePermission(context)) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        // Denied *and* the system will no longer show the dialog: the only way forward is Settings.
        blocked = !ok && activity?.let { !it.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) } == true
        if (ok) vm.listen()
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Coming back from the Settings app does not recompose on its own.
                Lifecycle.Event.ON_RESUME -> if (hasMicrophonePermission(context)) {
                    granted = true
                    blocked = false
                }
                // Backgrounding the app must close the microphone: this feature listens while you are
                // looking at it and at no other time. A rotation also stops the activity, hence the
                // `isChangingConfigurations` guard — otherwise turning the phone would silently throw
                // away a capture that is already half-recorded.
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) vm.cancel()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            // Navigating away — forwards or back — also ends the session, for the same reason. A
            // rotation disposes this composable too, and must not.
            if (activity?.isChangingConfigurations != true) vm.cancel()
        }
    }

    val listen: () -> Unit = {
        haptics.confirm()
        when {
            granted -> vm.listen()
            blocked -> context.openAppSettings()
            else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RizxIconButton(RizxIcons.Back, contentDescription = null, onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.recognition_title),
                style = sg(24, FontWeight.Bold, -0.02f),
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                RizxIconButton(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.recognition_history_clear),
                    onClick = { haptics.tick(); vm.clearHistory() },
                    tint = c.muted,
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "stage") {
                // Announced to TalkBack as it changes: the whole flow is a sequence of states, and a
                // screen reader user gets nothing from a signal meter.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    // `contentKey`, and every branch reading its own `shown` rather than the outer
                    // `state`. While a transition runs, the *outgoing* content is still composed —
                    // with the state it was built for — so a branch that reached back out to the
                    // current state would be rendering "Listening" from a state that had already
                    // become "Processing". The key is what keeps the animation coarse: without it,
                    // every amplitude tick would restart the transition.
                    AnimatedContent(
                        targetState = state,
                        contentKey = { stageOf(it, granted, blocked) },
                        label = "recognitionStage",
                    ) { shown ->
                        when (stageOf(shown, granted, blocked)) {
                            Stage.Permission -> PermissionStage(blocked = blocked, onGrant = listen)
                            Stage.Idle -> IdleStage(onListen = listen)
                            Stage.Listening -> ListeningStage(
                                shown as? RecognitionState.Listening ?: RecognitionState.Listening(0, 0f),
                                vm::cancel,
                            )
                            Stage.Processing -> ProcessingStage(vm::cancel)
                            Stage.Matched -> (shown as? RecognitionState.Matched)?.let { matched ->
                                MatchedStage(
                                    state = matched,
                                    onPlay = { haptics.confirm(); vm.play(it) },
                                    onSearch = { onSearch(it) },
                                    onShare = { context.shareRecognition(it) },
                                    onRetry = { vm.dismiss(); listen() },
                                )
                            }
                            Stage.NoMatch -> MessageStage(
                                title = stringResource(R.string.recognition_no_match),
                                body = stringResource(R.string.recognition_no_match_body),
                                onRetry = { vm.dismiss(); listen() },
                            )
                            Stage.Failed -> {
                                val failed = shown as? RecognitionState.Failed
                                MessageStage(
                                    title = stringResource(
                                        failed?.category?.messageRes() ?: R.string.recognition_error_unknown,
                                    ),
                                    body = null,
                                    onRetry = if (failed?.retryable != false) ({ vm.dismiss(); listen() }) else null,
                                )
                            }
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                item(key = "history-header") {
                    SectionHeader(
                        stringResource(R.string.recognition_history),
                        modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
                    )
                }
                items(history, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onPlay = { track -> haptics.confirm(); vm.play(track) },
                        onSearch = { onSearch(it) },
                        onForget = { haptics.tick(); vm.forget(entry.id) },
                    )
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
        }
    }
}

private enum class Stage { Permission, Idle, Listening, Processing, Matched, NoMatch, Failed }

private fun stageOf(state: RecognitionState, granted: Boolean, blocked: Boolean): Stage = when {
    // A running session keeps the stage even if the permission was revoked underneath it, so the user
    // sees the capture finish rather than the screen jumping back to a permission prompt.
    state is RecognitionState.Listening -> Stage.Listening
    state is RecognitionState.Processing -> Stage.Processing
    state is RecognitionState.Matched -> Stage.Matched
    state is RecognitionState.NoMatch -> Stage.NoMatch
    state is RecognitionState.Failed -> Stage.Failed
    !granted || blocked -> Stage.Permission
    else -> Stage.Idle
}

@Composable
private fun IdleStage(onListen: () -> Unit) {
    val c = RizxTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .cornerBrackets(c.hardLine)
                .clickableScale(onClick = onListen),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RizxIconButton(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    onClick = onListen,
                    size = 88.dp,
                    iconSize = 40.dp,
                    tint = c.onFill,
                    background = c.fill,
                )
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.recognition_cta), style = code(13, FontWeight.Bold), color = c.text)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.recognition_idle_body),
            style = mr(14, FontWeight.Normal),
            color = c.text2,
        )
        Spacer(Modifier.height(6.dp))
        CodeLabel(stringResource(R.string.recognition_playback_paused))
    }
}

@Composable
private fun ListeningStage(state: RecognitionState.Listening, onCancel: () -> Unit) {
    val c = RizxTheme.colors
    // Smoothed, because a raw peak per audio chunk reads as a flicker rather than a level.
    val level by animateFloatAsState(targetValue = state.amplitude, label = "level")

    Column(Modifier.fillMaxWidth()) {
        Eyebrow(stringResource(R.string.recognition_listening))
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .cornerBrackets(c.hardLine),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LevelMeter(level)
                Spacer(Modifier.height(16.dp))
                Text(
                    formatElapsed(state.elapsedMs),
                    style = code(22, FontWeight.Bold),
                    color = c.text,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Readout(stringResource(R.string.recognition_signal), "${(level * 100).roundToInt()}%")
        Readout(stringResource(R.string.recognition_format), stringResource(R.string.recognition_format_value))
        Spacer(Modifier.height(16.dp))
        RizxActionButton(
            icon = RizxIcons.Close,
            label = stringResource(R.string.recognition_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Purely decorative: the flow never depends on it, and it is not announced. */
@Composable
private fun LevelMeter(level: Float) {
    val c = RizxTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(56.dp).semantics { contentDescription = "" },
    ) {
        val bars = 13
        repeat(bars) { index ->
            // A shallow arch, loudest in the middle, so the meter reads as one shape rather than
            // thirteen independent bars.
            val distance = kotlin.math.abs(index - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val height = (8f + level * 48f * (1f - distance * 0.7f)).coerceIn(4f, 56f)
            Box(
                Modifier
                    .width(6.dp)
                    .height(height.dp)
                    .background(if (level > 0.02f) c.fill else c.line),
            )
        }
    }
}

@Composable
private fun ProcessingStage(onCancel: () -> Unit) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Eyebrow(stringResource(R.string.recognition_processing))
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f).cornerBrackets(c.hardLine),
            contentAlignment = Alignment.Center,
        ) {
            DotMatrixSpinner(color = c.text, diameter = 44.dp)
        }
        Spacer(Modifier.height(16.dp))
        RizxActionButton(
            icon = RizxIcons.Close,
            label = stringResource(R.string.recognition_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MatchedStage(
    state: RecognitionState.Matched,
    onPlay: (Track) -> Unit,
    onSearch: (String) -> Unit,
    onShare: (RecognitionState.Matched) -> Unit,
    onRetry: () -> Unit,
) {
    val c = RizxTheme.colors
    val match = state.match
    Column(Modifier.fillMaxWidth()) {
        Eyebrow(stringResource(R.string.recognition_match))
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                tintIndex = tintFor(match.providerTrackId),
                initial = match.title.firstOrNull()?.uppercase(),
                imageUrl = match.artworkHqUrl ?: match.artworkUrl,
                modifier = Modifier.size(96.dp),
                initialSize = 34,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    match.title,
                    style = sg(20, FontWeight.Bold, -0.02f),
                    color = c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    match.artist,
                    style = mr(14, FontWeight.Medium),
                    color = c.text2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                match.album?.let {
                    Text(it, style = mr(13, FontWeight.Normal), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        match.isrc?.let { Readout(stringResource(R.string.recognition_isrc), it) }
        match.label?.let { Readout(stringResource(R.string.recognition_label), it) }
        match.releaseDate?.let { Readout(stringResource(R.string.recognition_released), it) }
        Readout(
            stringResource(R.string.recognition_resolved),
            state.resolvedTrack?.source?.provider ?: stringResource(R.string.recognition_resolved_none),
        )

        Spacer(Modifier.height(16.dp))
        val track = state.resolvedTrack
        if (track != null) {
            RizxActionButton(
                icon = Icons.Filled.PlayArrow,
                label = stringResource(R.string.recognition_play),
                onClick = { onPlay(track) },
                modifier = Modifier.fillMaxWidth(),
                prominent = true,
            )
        } else {
            Text(
                stringResource(R.string.recognition_unresolved_body),
                style = mr(13, FontWeight.Normal),
                color = c.text2,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            RizxActionButton(
                icon = Icons.Filled.Search,
                label = stringResource(R.string.recognition_search),
                onClick = { onSearch("${match.title} ${match.artist}") },
                modifier = Modifier.fillMaxWidth(),
                prominent = true,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (match.externalUrl != null) {
                RizxActionButton(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.recognition_share),
                    onClick = { onShare(state) },
                    modifier = Modifier.weight(1f),
                )
            }
            RizxActionButton(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.recognition_try_again),
                onClick = onRetry,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MessageStage(title: String, body: String?, onRetry: (() -> Unit)?) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Eyebrow(title)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f).cornerBrackets(c.hardLine),
            contentAlignment = Alignment.Center,
        ) {
            RizxIconButton(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                onClick = {},
                size = 64.dp,
                iconSize = 32.dp,
                tint = c.muted,
            )
        }
        body?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = mr(14, FontWeight.Normal), color = c.text2)
        }
        onRetry?.let {
            Spacer(Modifier.height(16.dp))
            RizxActionButton(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.recognition_try_again),
                onClick = it,
                modifier = Modifier.fillMaxWidth(),
                prominent = true,
            )
        }
    }
}

@Composable
private fun PermissionStage(blocked: Boolean, onGrant: () -> Unit) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.recognition_permission_title),
            style = sg(18, FontWeight.Bold, -0.02f),
            color = c.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (blocked) R.string.recognition_permission_body_blocked else R.string.recognition_permission_body,
            ),
            style = mr(14, FontWeight.Normal),
            color = c.text2,
        )
        Spacer(Modifier.height(16.dp))
        RizxActionButton(
            icon = Icons.Filled.Mic,
            label = stringResource(
                if (blocked) R.string.recognition_permission_cta_blocked else R.string.recognition_permission_cta,
            ),
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth(),
            prominent = true,
        )
    }
}

@Composable
private fun HistoryRow(
    entry: RecognitionHistoryItem,
    onPlay: (Track) -> Unit,
    onSearch: (String) -> Unit,
    onForget: () -> Unit,
) {
    val c = RizxTheme.colors
    val match = entry.match
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickableScale {
                entry.resolvedTrack?.let(onPlay) ?: onSearch("${match.title} ${match.artist}")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            tintIndex = tintFor(match.providerTrackId),
            initial = match.title.firstOrNull()?.uppercase(),
            imageUrl = match.artworkUrl,
            modifier = Modifier.size(48.dp),
            initialSize = 18,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(match.title, style = mr(15, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(match.artist, style = mr(13, FontWeight.Normal), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RizxIconButton(
            Icons.Filled.DeleteOutline,
            contentDescription = stringResource(R.string.recognition_history_delete),
            onClick = onForget,
            tint = c.muted,
            iconSize = 18.dp,
        )
    }
}

/** A label/value line in the technical readout. */
@Composable
private fun Readout(label: String, value: String) {
    val c = RizxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        CodeLabel(label, modifier = Modifier.width(96.dp))
        Text(value, style = code(11, FontWeight.Medium), color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    val tenths = (ms % 1000) / 100
    return "%02d:%02d.%d".format(seconds / 60, seconds % 60, tenths)
}

private fun RecognitionError.messageRes(): Int = when (this) {
    RecognitionError.PERMISSION -> R.string.recognition_error_permission
    RecognitionError.MICROPHONE_UNAVAILABLE -> R.string.recognition_error_microphone
    RecognitionError.RECORDING_FAILED, RecognitionError.RESAMPLING_FAILED, RecognitionError.SIGNATURE_FAILED ->
        R.string.recognition_error_recording
    RecognitionError.NETWORK -> R.string.recognition_error_network
    RecognitionError.RATE_LIMITED -> R.string.recognition_error_rate_limited
    RecognitionError.SERVICE_UNAVAILABLE -> R.string.recognition_error_unavailable
    RecognitionError.INVALID_RESPONSE -> R.string.recognition_error_invalid
    RecognitionError.UNKNOWN -> R.string.recognition_error_unknown
}

/**
 * Shares the identification the way the service's own page would be shared: what it was, and a link.
 * Nothing about the capture leaves with it.
 */
private fun Context.shareRecognition(state: RecognitionState.Matched) {
    val what = "${state.match.title} — ${state.match.artist}"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, what)
        putExtra(Intent.EXTRA_TEXT, listOfNotNull(what, state.match.externalUrl).joinToString("\n"))
    }
    runCatching { startActivity(Intent.createChooser(send, null)) }
}

private fun hasMicrophonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
