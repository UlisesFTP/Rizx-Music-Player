package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/**
 * The brutalist picker for choices whose options need explaining — every row carries a caption, because
 * with these the difference *is* the point.
 *
 * Lives here rather than in Settings because the same shell now asks a question outside Settings too
 * (whether downloads should also be saved to the phone). [current] is nullable for exactly that case:
 * a question being put for the first time has no option ticked yet.
 */
@Composable
fun <T> CaptionedOptionDialog(
    title: String,
    options: List<T>,
    current: T?,
    label: @Composable (T) -> String,
    caption: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                // Scrollable because one caller's option list is not fixed: the Home-feed picker holds
                // one row per registered dashboard source and grows with every plugin installed. Six
                // captioned rows already reach the bottom of a short screen, and without this the last
                // ones are simply unreachable — the dialog clips rather than scrolls.
                .heightIn(max = MAX_HEIGHT)
                .background(c.elev)
                .border(1.5.dp, c.hardLine)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Text(
                title,
                style = sg(20, FontWeight.Bold, -0.01f),
                color = c.text,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            options.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = { onSelect(option) })
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label(option), style = mr(15, FontWeight.SemiBold), color = c.text)
                        Text(
                            caption(option),
                            style = mr(12, FontWeight.Normal),
                            color = c.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (option == current) {
                        Icon(
                            RizxIcons.Check,
                            "Selected",
                            tint = c.redAccent,
                            modifier = Modifier.padding(start = 12.dp).size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Leaves the dialog clearly inside a short screen, so it reads as a card rather than a full page. */
private val MAX_HEIGHT = 520.dp
