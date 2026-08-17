package fm.rizx.player.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.InkFrame
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tileUrl
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation

const val QUICK_PICK_LIMIT = 6

/** Provider identity is the only valid dedupe key; display metadata and URLs are deliberately ignored. */
fun quickPicks(tracks: List<Track>, limit: Int = QUICK_PICK_LIMIT): List<Track> =
    if (limit <= 0) emptyList()
    else tracks.distinctBy { it.source.identityKey }.take(limit)

/**
 * Spotify-like quick access without borrowing Spotify's visual language: two compact columns, three
 * rows, hard Rizx frames, and enough text to identify a song before tapping it.
 */
@Composable
fun QuickPickGrid(
    tracks: List<Track>,
    currentSource: ProviderRef?,
    isPlaying: Boolean,
    motionEnabled: Boolean,
    onPlay: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picks = remember(tracks) { quickPicks(tracks) }
    if (picks.isEmpty()) return
    val currentDescription = androidx.compose.ui.res.stringResource(R.string.queue_now_current_cd)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        picks.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { track ->
                    val current = currentSource?.identityKey == track.source.identityKey
                    QuickPickTile(
                        track = track,
                        current = current,
                        playing = current && isPlaying,
                        motionEnabled = motionEnabled,
                        currentDescription = currentDescription,
                        onClick = { onPlay(track) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickPickTile(
    track: Track,
    current: Boolean,
    playing: Boolean,
    motionEnabled: Boolean,
    currentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    val artist = track.artists.joinToString { it.name }.ifBlank { track.source.provider }
    val spoken = listOf(
        track.title,
        artist,
        track.source.provider,
        currentDescription.takeIf { current },
    ).filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
    Row(
        modifier
            .heightIn(min = 68.dp)
            .paperElevation()
            .background(if (current) c.rowHover else c.elev)
            .border(InkFrame, if (current) c.redAccent else c.hardLine, RectangleShape)
            .semantics {
                role = Role.Button
                selected = current
                contentDescription = spoken
            }
            .clickableScale(scale = 0.975f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            tintFor(track.source.identityKey),
            initial = null,
            Modifier.size(68.dp),
            imageUrl = track.artwork.tileUrl(),
            borderWidth = 0.dp,
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                track.title,
                style = mr(12, FontWeight.Bold),
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist,
                style = mr(10, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                track.source.provider.uppercase(),
                style = code(8, FontWeight.Bold),
                color = if (current) c.redAccent else c.text2,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (current) {
            PlayingBars(
                playing = playing,
                motionEnabled = motionEnabled,
                color = c.redAccent,
                modifier = Modifier.padding(end = 9.dp).size(18.dp),
            )
        }
    }
}

/** A compact five-face used by the visible Surprise action in the section header. */
@Composable
fun DiceFace(pip: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension * 0.22f
        val span = size.minDimension - side
        listOf(0f to 0f, 1f to 0f, 0.5f to 0.5f, 0f to 1f, 1f to 1f).forEach { (fx, fy) ->
            drawRect(pip, topLeft = Offset(fx * span, fy * span), size = Size(side, side))
        }
    }
}

@Composable
private fun PlayingBars(
    playing: Boolean,
    motionEnabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val phase = if (playing && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "homePlaying")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(720), RepeatMode.Restart),
            label = "homePlayingPhase",
        )
        animatedPhase
    } else {
        0f
    }
    Canvas(modifier) {
        val barWidth = size.width / 5f
        val gap = barWidth
        repeat(3) { index ->
            val wave = if (playing && motionEnabled) {
                kotlin.math.abs(kotlin.math.sin((phase + index * 0.22f) * Math.PI)).toFloat()
            } else if (playing) {
                listOf(0.45f, 0.9f, 0.62f)[index]
            } else {
                0.26f
            }
            val height = size.height * (0.28f + wave * 0.72f)
            drawRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
}
