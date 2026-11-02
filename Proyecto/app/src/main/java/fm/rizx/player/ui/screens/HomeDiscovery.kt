package fm.rizx.player.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.home.HideTarget
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/** The localized name of a genre, for the "Your genre: …" shelf. */
@Composable
internal fun genreLabel(genre: SoundGenre): String = stringResource(
    when (genre) {
        SoundGenre.REGGAETON -> R.string.genre_reggaeton
        SoundGenre.HIPHOP -> R.string.genre_hiphop
        SoundGenre.ELECTRONIC -> R.string.genre_electronic
        SoundGenre.REGGAE_DANCEHALL -> R.string.genre_reggae
        SoundGenre.POP -> R.string.genre_pop
        SoundGenre.ROCK -> R.string.genre_rock
        SoundGenre.METAL -> R.string.genre_metal
        SoundGenre.INDIE_ALT -> R.string.genre_indie
        SoundGenre.LATIN_REGIONAL -> R.string.genre_latin_regional
        SoundGenre.LATIN_TROPICAL -> R.string.genre_latin_tropical
        SoundGenre.RNB_SOUL -> R.string.genre_rnb
        SoundGenre.KPOP_JPOP -> R.string.genre_kpop
        SoundGenre.SOUNDTRACK -> R.string.genre_soundtrack
        SoundGenre.ACOUSTIC_FOLK -> R.string.genre_folk
        SoundGenre.COUNTRY -> R.string.genre_country
        SoundGenre.JAZZ -> R.string.genre_jazz
        SoundGenre.CLASSICAL -> R.string.genre_classical
        SoundGenre.LOFI_CHILL -> R.string.genre_lofi
        SoundGenre.SPOKEN, SoundGenre.UNKNOWN -> R.string.genre_other
    },
)

/**
 * The long-press sheet on a Home card: one action, "Not interested", worded for what it will hide. One
 * action rather than a menu because that is the only thing the Home lacked — opening and playing are
 * already a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotInterestedSheet(target: HideTarget, onHide: () -> Unit, onDismiss: () -> Unit) {
    val c = RizxTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.elev, contentColor = c.text) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(target.label, style = sg(20, FontWeight.Bold, -0.02f), color = c.text)
            Text(
                when (target) {
                    is HideTarget.Artist -> stringResource(R.string.discovery_hide_artist_caption, target.name)
                    is HideTarget.Playlist -> stringResource(R.string.discovery_hide_playlist_caption)
                },
                style = mr(12, FontWeight.Medium),
                color = c.muted,
            )
            Row(Modifier.padding(top = 10.dp)) {
                DiscoveryButton(stringResource(R.string.discovery_not_interested), filled = true, onClick = onHide)
            }
        }
    }
}

/**
 * The cold-start card: pick a few artists so the personalized half has something to stand on before
 * there is any history. Offered once — "Not now" is remembered — and gone the moment anything is
 * played or liked. The choices come from the charts already on screen, so it costs no request.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeedPickerCard(artists: List<String>, onDone: (List<String>) -> Unit, onNotNow: () -> Unit) {
    val c = RizxTheme.colors
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 16.dp)
            .border(1.5.dp, c.hardLine, RectangleShape)
            .background(c.elev)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.seed_title), style = sg(16, FontWeight.Bold), color = c.text)
        Text(stringResource(R.string.seed_caption), style = mr(12, FontWeight.Medium), color = c.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            artists.forEach { name ->
                RizxChip(name, active = name in picked, onClick = {
                    picked = if (name in picked) picked - name else picked + name
                })
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DiscoveryButton(
                stringResource(R.string.seed_done, picked.size),
                filled = true,
                enabled = picked.size >= MIN_SEEDS,
                onClick = { onDone(picked) },
            )
            DiscoveryButton(stringResource(R.string.seed_not_now), filled = false, onClick = onNotNow)
        }
    }
}

@Composable
private fun DiscoveryButton(label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Box(
        Modifier
            .clickableScale(scale = 0.97f, enabled = enabled, onClick = onClick)
            .border(1.5.dp, if (enabled) c.hardLine else c.line2, RectangleShape)
            .background(if (filled && enabled) c.text else c.elev)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(
            label,
            style = mr(12, FontWeight.Bold),
            color = when {
                !enabled -> c.muted
                filled -> c.elev
                else -> c.text
            },
        )
    }
}

/** Fewer than this and the personalized rows have too little to stand on. */
internal const val MIN_SEEDS = 3

/** How many chart artists the picker offers. */
internal const val SEED_CHOICES = 18
