package fm.rizx.player.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.util.rememberRizxHaptics

/** Sharp-cornered settings switch (48×28) with a square knob — brutalist ink border + snappy knob. */
@Composable
fun RizxToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    // Spring gives the knob a mechanical snap (Nothing-OS feel) instead of a linear glide.
    val knobX by animateDpAsState(
        if (checked) 23.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "toggleKnob",
    )
    Box(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(RectangleShape)
            .background(if (checked) c.redAccent else c.elev2)
            .border(1.5.dp, c.hardLine, RectangleShape)
            .clickableScale(scale = 0.94f, haptic = false) {
                haptics.toggle(!checked)
                onToggle()
            },
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(22.dp)
                .background(if (checked) c.onRed else c.muted),
        )
    }
}
