package fm.rizx.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.util.rememberRizxHaptics

/**
 * Play/pause control with an optional soft "pulse" halo behind it. The halo only animates
 * while playing; pass `glow = false` (e.g. the light/Paper theme) to drop it entirely.
 * `shape` controls the button corners (sharp by default; Now Playing passes a rounded shape).
 */
@Composable
fun PulsingPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    iconSize: Dp = 34.dp,
    fillColor: Color = RizxTheme.colors.fill,
    onFillColor: Color = RizxTheme.colors.onFill,
    glowColor: Color = RizxTheme.colors.accent,
    shape: Shape = RectangleShape,
    glow: Boolean = true,
    loading: Boolean = false,
) {
    val haptics = rememberRizxHaptics()
    val haloScale: Float
    val haloAlpha: Float
    if (isPlaying && glow) {
        val transition = rememberInfiniteTransition(label = "pulse")
        haloScale = transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.16f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "pulseScale",
        ).value
        haloAlpha = transition.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.48f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "pulseAlpha",
        ).value
    } else {
        haloScale = 1.05f
        haloAlpha = if (glow) 0.22f else 0f
    }

    Box(modifier.size(size + 18.dp), contentAlignment = Alignment.Center) {
        if (glow && android.os.Build.VERSION.SDK_INT >= 31) {
            // Single soft halo (subtle). Only on 31+: Compose blur is RenderEffect-backed and silently
            // no-ops below, which would leave a hard-edged disc — better no halo at all there.
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = haloScale
                        scaleY = haloScale
                        alpha = haloAlpha
                    }
                    .blur(16.dp, BlurredEdgeTreatment.Unbounded)
                    .clip(CircleShape)
                    .background(glowColor),
            )
        }
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(fillColor)
                // Blocked while loading — the tap is a no-op until the track is ready.
                .clickableScale(scale = 0.93f, enabled = !loading, haptic = false) {
                    haptics.confirm()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                // Nothing-OS loader while the stream resolves/buffers (only animates before audio starts).
                DotMatrixSpinner(color = onFillColor, diameter = iconSize)
            } else {
                // Play↔pause icon swaps with a quick scale+fade (mechanical morph).
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.7f)) togetherWith
                            (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.7f))
                    },
                    label = "playPauseIcon",
                ) { playing ->
                    Icon(
                        if (playing) RizxIcons.Pause else RizxIcons.Play,
                        contentDescription = if (playing) stringResource(R.string.action_pause) else stringResource(R.string.action_play),
                        tint = onFillColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}
