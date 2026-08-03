package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.rizx.player.domain.model.Track
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/**
 * An editorial playlist as a full card: identity up top, a peek at its first songs, and a PLAY block —
 * the "here's what's inside" presentation streaming feeds use for their featured shelf, in Rizx's own
 * ink. The card body opens the playlist; only the PLAY block plays it (its own click node, so the two
 * targets never fight over one tap).
 */
@Composable
fun FeaturedPlaylistCard(
    name: String,
    subtitle: String,
    coverUrl: String?,
    tintKey: String,
    preview: List<Track>,
    playLabel: String,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    Column(
        modifier
            .width(CARD_WIDTH)
            .clickableScale(scale = 0.985f, onClick = onOpen)
            .border(InkFrame, c.hardLine, RectangleShape)
            .background(c.elev)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoverArt(
                tintFor(tintKey), initial = name.take(1), Modifier.size(76.dp), initialSize = 26,
                imageUrl = coverUrl, borderColor = c.hardLine, borderWidth = InkFrame,
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = sg(17, FontWeight.Bold, -0.01f), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        preview.take(PREVIEW_ROWS).forEach { track ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CoverArt(
                    tintFor(track.source.id), initial = null, Modifier.size(38.dp),
                    imageUrl = track.artwork.tileUrl(), borderColor = c.hardLine, borderWidth = 1.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = mr(13, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artists.joinToString { it.name }.ifEmpty { "—" },
                        style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            playLabel.uppercase(),
            style = code(11, FontWeight.Bold),
            color = c.onFill,
            modifier = Modifier
                .background(c.fill)
                .clickableScale(scale = 0.95f, onClick = onPlay)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

private val CARD_WIDTH = 316.dp

/** Three songs is a peek; the card's subtitle already says how many the playlist really holds. */
private const val PREVIEW_ROWS = 3
