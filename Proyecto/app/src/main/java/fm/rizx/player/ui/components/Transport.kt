package fm.rizx.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.util.rememberRizxHaptics
import kotlinx.coroutines.launch

/**
 * The player's transport controls, in the brutalist idiom the reference sets: the glyph inside a square
 * ink frame, and a **small red corner marker on the buttons that carry state**.
 *
 * That marker is a rule, not decoration. Shuffle, repeat and play/pause each hold a state you can be
 * wrong about, so each shows it; skip-previous and skip-next hold none, so they wear no marker. The play
 * button additionally gets HUD corner brackets, which is what makes it read as the centre of the row
 * without simply being the biggest thing on it.
 *
 * **Every animation here is event-driven** — springs on a state change, a one-shot flick on a tap.
 * Nothing animates while the player sits idle, which is the standing rule on this screen: a continuous
 * driver competes with audio decoding for frames on weak hardware (it is why the play button's blurred
 * halo was dropped).
 */

/** Whether a transport button carries a state marker, and what that state is. */
enum class TransportMarker { None, Off, On }

@Composable
fun TransportButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = TRANSPORT_BUTTON,
    iconSize: Dp = 24.dp,
    marker: TransportMarker = TransportMarker.None,
    /** `-1` flicks the glyph left on tap, `+1` right — the directional cue for previous / next. */
    nudge: Int = 0,
) {
    val c = RizxTheme.colors
    val scope = rememberCoroutineScope()
    val flick = remember { Animatable(0f) }
    val on = marker == TransportMarker.On
    // Springs rather than tweens: a mode coming on should overshoot a little and settle, the way a
    // physical switch does.
    val markerSide by animateDpAsState(
        if (on) MARKER_ON else MARKER_OFF,
        spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "markerSide",
    )
    val markerColor by animateColorAsState(
        if (on) c.redAccent else c.line2,
        tween(200),
        label = "markerColor",
    )
    // An inactive *mode* dims its glyph; a button with no mode at all is never dim — it is always live.
    val glyph by animateColorAsState(
        if (marker == TransportMarker.Off) c.text2 else c.text,
        tween(200),
        label = "glyph",
    )
    val frame by animateColorAsState(
        if (on) c.redAccent.copy(alpha = 0.5f) else c.hardLine,
        tween(200),
        label = "frame",
    )
    Box(
        modifier
            .size(size)
            .background(c.elev)
            .border(FRAME_WEIGHT, frame, RectangleShape)
            .clickableScale(scale = 0.9f) {
                if (nudge != 0) {
                    scope.launch {
                        flick.snapTo(0f)
                        flick.animateTo(nudge.toFloat(), tween(90, easing = FastOutSlowInEasing))
                        flick.animateTo(0f, spring(dampingRatio = 0.36f, stiffness = Spring.StiffnessLow))
                    }
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = glyph,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { translationX = flick.value * NUDGE.toPx() },
        )
        if (marker != TransportMarker.None) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MARKER_INSET)
                    .size(markerSide)
                    .background(markerColor),
            )
        }
    }
}

/**
 * Play / pause: a filled block inside HUD corner brackets, with the state marker straddling its corner.
 *
 * The brackets **lock on** when playback starts — their arms lengthen on a spring — which is the whole
 * of the "it's running" feedback. It replaces a continuously-animated blurred halo, the single most
 * expensive thing that was on this screen.
 */
@Composable
fun TransportPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    iconSize: Dp = 36.dp,
    fillColor: Color = RizxTheme.colors.fill,
    onFillColor: Color = RizxTheme.colors.onFill,
    loading: Boolean = false,
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    val arm by animateDpAsState(
        if (isPlaying) 15.dp else 10.dp,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "bracketArm",
    )
    val markerColor by animateColorAsState(
        if (isPlaying) c.redAccent else c.line2,
        tween(220),
        label = "playMarker",
    )
    Box(
        modifier
            .size(size + BRACKET_GUTTER * 2)
            .cornerBrackets(c.hardLine, len = arm, thickness = FRAME_WEIGHT, inset = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .background(fillColor)
                // Blocked while loading — the tap is a no-op until the track is ready.
                .clickableScale(scale = 0.93f, enabled = !loading, haptic = false) {
                    haptics.confirm()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                DotMatrixSpinner(color = onFillColor, diameter = iconSize)
            } else {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.68f)) togetherWith
                            (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.68f))
                    },
                    label = "playPauseIcon",
                ) { playing ->
                    Icon(
                        if (playing) RizxIcons.Pause else RizxIcons.Play,
                        contentDescription = if (playing) {
                            stringResource(R.string.action_pause)
                        } else {
                            stringResource(R.string.action_play)
                        },
                        tint = onFillColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
        // Centred on the filled block's bottom-right corner: BottomEnd puts its own corner `p` from the
        // outer edge, so `p = gutter - side/2` lands its middle exactly on the block's.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(BRACKET_GUTTER - PLAY_MARKER / 2)
                .size(PLAY_MARKER)
                .background(markerColor),
        )
    }
}

/**
 * One size for all four side buttons, so the row reads as a set. At 48.dp it is also exactly the minimum
 * touch target, and the five controls plus their gaps still fit a 320.dp-wide screen — which the previous
 * mix of 46, 50 and 92.dp did not.
 */
val TRANSPORT_BUTTON = 48.dp

private val FRAME_WEIGHT = 1.5.dp
private val MARKER_ON = 6.dp
private val MARKER_OFF = 4.dp
private val MARKER_INSET = 4.dp
private val PLAY_MARKER = 7.dp

/** Room around the filled block for its brackets to sit in. */
private val BRACKET_GUTTER = 9.dp

/** How far a tap flicks the skip glyph before it springs back. */
private val NUDGE = 4.dp
