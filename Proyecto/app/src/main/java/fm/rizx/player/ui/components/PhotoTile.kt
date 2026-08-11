package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.catBg
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.paperElevation
import fm.rizx.player.ui.theme.sg

/**
 * A browse mosaic: a full-bleed photo under a bottom-weighted scrim, with a HUD serial and a
 * display-font label.
 *
 * Shared by Search's genre wall and Home's mood grid, because those two are the same gesture — "show
 * me a shelf of the catalogue" — and they looked like two different apps when each drew its own.
 *
 * [imageUrl] may be null (a shelf whose source publishes no artwork); the [tint] block underneath is
 * then the tile, which is why it is painted unconditionally.
 */
@Composable
fun PhotoTile(
    label: String,
    imageUrl: String?,
    code: String,
    tint: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    Box(
        modifier
            .height(116.dp)
            .paperElevation()
            .clip(RectangleShape)
            .background(catBg(tint, c.isDark))
            .border(1.dp, c.line, RectangleShape)
            .clickableScale(scale = 0.98f, onClick = onClick),
    ) {
        if (imageUrl != null) {
            coil.compose.AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Bottom-weighted scrim keeps the label legible over bright or busy photos, in either theme.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        0.55f to Color.Black.copy(alpha = 0.30f),
                        1f to Color.Black.copy(alpha = 0.80f),
                    ),
                ),
        )
        // HUD serial (red tick + mono code) — ties the photo tiles into the spec-sheet language.
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(c.redAccent))
            Text(code, style = code(10, FontWeight.Bold), color = Color.White.copy(alpha = 0.82f))
        }
        Text(
            label,
            style = sg(18, FontWeight.Bold, -0.01f),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 13.dp, end = 12.dp, bottom = 11.dp),
        )
    }
}

/** A stable tint for a tile whose source gives it no colour of its own. */
fun photoTileTint(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 8
