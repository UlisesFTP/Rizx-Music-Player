package fm.rizx.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.model.MoodStation

/**
 * "Moods & genres": the provider's curated stations as a two-column mosaic.
 *
 * The stations' titles are the content ("Chill Out", "¡Fiesta!", "Día lluvioso"…) and arrive already
 * localized by the provider, but each also ships a cover the app used to throw away — so the grid now
 * looks like Search's browse wall, which is the same gesture. Artwork goes through
 * [tileUrl][fm.rizx.player.ui.components.tileUrl], so data saver drops it to the cheap rung.
 *
 * [stations] pairs each station with the provider id that supplied it, because that provider is the
 * only one able to resolve it to tracks; both travel to [onOpen] and on into the station screen.
 * [onSeeAll] is absent when the caller is already showing all of them.
 */
@Composable
fun MoodGrid(
    stations: List<Pair<String, MoodStation>>,
    onOpen: (providerId: String, station: MoodStation) -> Unit,
    modifier: Modifier = Modifier,
    heading: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    if (stations.isEmpty()) return
    Column(modifier) {
        val title = heading ?: stringResource(R.string.home_moods_heading)
        SectionHeader(
            title,
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            action = onSeeAll?.let { stringResource(R.string.action_see_all) },
            onAction = onSeeAll,
        )
        stations.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEachIndexed { colIndex, (providerId, station) ->
                    val index = rowIndex * 2 + colIndex
                    PhotoTile(
                        label = station.title,
                        imageUrl = station.artwork.tileUrl(),
                        code = "M%02d".format(index + 1),
                        tint = photoTileTint(station.id),
                        onClick = { onOpen(providerId, station) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
