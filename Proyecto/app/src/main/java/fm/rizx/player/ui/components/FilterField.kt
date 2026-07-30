package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/**
 * A list's **own** search bar: it narrows the rows under it and nothing else.
 *
 * Deliberately smaller than the Search screen's field — shorter, thinner ink, a 17dp glyph. That field is
 * the subject of its screen; this one sits between a header and the rows it filters, and if it carried the
 * same weight it would read as the page's main input and push the content it exists to serve off-screen.
 *
 * Filtering is live (there is no submit — [fm.rizx.player.ui.util.ListFilter] runs in memory, so there is
 * nothing to wait for); the IME's search key only puts the keyboard away.
 */
@Composable
fun RizxFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.filter_hint),
) {
    val c = RizxTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused || query.isNotEmpty()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(c.inset)
            .border(1.dp, if (active) c.redAccent else c.line, RectangleShape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(RizxIcons.Search, null, tint = if (active) c.redAccent else c.muted, modifier = Modifier.size(17.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).padding(vertical = 11.dp),
            singleLine = true,
            textStyle = mr(14, FontWeight.Medium).copy(color = c.text),
            cursorBrush = SolidColor(c.redAccent),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(hint, style = mr(14, FontWeight.Medium), color = c.muted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        // Clearing is the way back to the whole list, so it gets a real target (36dp) around its 18dp
        // glyph rather than the bare icon a denser row would use.
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(36.dp).clickableScale(scale = 0.86f) { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(RizxIcons.Close, stringResource(R.string.action_clear), tint = c.text2, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * What a filter shows when it excludes everything. Says the list was searched and *what* was searched for —
 * an unqualified "nothing here" reads as a list that emptied itself.
 */
@Composable
fun FilterEmpty(query: String, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Column(
        modifier.fillMaxWidth().padding(top = 34.dp, bottom = 18.dp, start = 18.dp, end = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(RizxIcons.Search, null, tint = c.muted, modifier = Modifier.size(34.dp))
        Text(
            stringResource(R.string.filter_no_matches_title),
            style = sg(17, FontWeight.Bold),
            color = c.text,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(R.string.filter_no_matches_body, query),
            style = mr(13, FontWeight.Medium),
            color = c.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
