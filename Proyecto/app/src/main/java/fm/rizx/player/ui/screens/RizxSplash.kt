package fm.rizx.player.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.ui.theme.sg

/**
 * The brand splash: the complete horizontal lockup — logomark plus the "Rizx." wordmark with its accent
 * dot — centred on the brand's dark field.
 *
 * Drawn in Compose rather than by the platform SplashScreen API on purpose: that API masks its icon slot
 * to a circle, so it can only ever show the bare symbol, never the full lockup. The launch is therefore
 * staged — the system splash holds the same dark field with the symbol while the app starts, then this
 * takes over and completes the logo.
 *
 * The lockup is painted at full opacity from the first frame. It must not fade in: the system splash
 * window is already gone by then, so a fade would show an empty field during the handoff.
 */
@Composable
fun RizxSplash(modifier: Modifier = Modifier) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }

    // A restrained settle — scale only, never opacity, so nothing is ever half-drawn.
    val settle by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(durationMillis = 380),
        label = "splashSettle",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(SplashField),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .padding(horizontal = 28.dp)
                .scale(0.97f + 0.03f * settle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_rizx_symbol_light),
                contentDescription = "Rizx",
                // Pinned to the mark's own 250x290 proportions so it can never distort.
                modifier = Modifier
                    .height(SymbolHeight)
                    .width(SymbolHeight * 250f / 290f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rizx", style = sg(58, FontWeight.Medium, -0.02f), color = Paper)
                Text(".", style = sg(58, FontWeight.Bold, -0.02f), color = Accent)
            }
        }
    }
}

/** Matches `@color/rizx_splash_bg`, so the system splash hands off with no visible colour step. */
private val SplashField = Color(0xFF0A0A0B)
private val Paper = Color(0xFFF3ECE2)
private val Accent = Color(0xFFFB4312)
private val SymbolHeight = 92.dp
