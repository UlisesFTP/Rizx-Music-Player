package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code

/**
 * "Moods & genres": the provider's curated stations as a two-column grid of tap-to-play chips.
 * Text only, on purpose — the stations' titles ARE the content ("Chill Out", "¡Fiesta!", Pop…), they
 * arrive already localized by the provider, and a grid with no artwork costs nothing on data saver.
 *
 * [stations] pairs each station with the provider id that supplied it, because that provider is the
 * only one able to resolve the station to tracks; [onPlay] receives both plus the localized queue
 * label so the queue can introduce itself ("Station · Chill Out").
 */
@Composable
fun MoodGrid(
    stations: List<Pair<String, MoodStation>>,
    onPlay: (providerId: String, station: MoodStation, queueLabel: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stations.isEmpty()) return
    Column(modifier) {
        SectionHeader(
            stringResource(R.string.home_moods_heading),
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        stations.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { (providerId, station) ->
                    val queueLabel = stringResource(R.string.home_station_of, station.title)
                    MoodChip(station.title, Modifier.weight(1f)) { onPlay(providerId, station, queueLabel) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MoodChip(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Box(
        modifier
            .clickableScale(scale = 0.97f, onClick = onClick)
            .border(1.5.dp, c.hardLine, RectangleShape)
            .background(c.elev),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title.uppercase(),
            style = code(11, FontWeight.Bold),
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 15.dp),
        )
    }
}
