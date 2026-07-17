package fm.rizx.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.player.LyricsUiState
import fm.rizx.player.ui.player.LyricsViewModel
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

@Composable
fun LyricsScreen(onBack: () -> Unit, vm: LyricsViewModel = hiltViewModel()) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                RizxIcons.Back, "Back", tint = c.text,
                modifier = Modifier.size(26.dp).clickableScale(scale = 0.88f, onClick = onBack),
            )
            Text("Lyrics", style = sg(28, FontWeight.Bold, -0.02f), color = c.text)
        }

        when (val s = state) {
            LyricsUiState.NoTrack -> Centered("Nothing is playing.")
            LyricsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DotMatrixSpinner(color = c.accent, diameter = 34.dp)
            }
            LyricsUiState.Offline -> Centered("You're offline. Connect and try again.")
            is LyricsUiState.Empty -> Centered("No lyrics found for “${s.title}”.")
            is LyricsUiState.Error -> Centered(s.message)
            is LyricsUiState.Text -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
                Text(s.title, style = sg(20, FontWeight.Bold, -0.01f), color = c.text)
                Text(s.artist, style = mr(13, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
                Text(s.lyrics, style = mr(15, FontWeight.Medium), color = c.text2)
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = mr(14, FontWeight.Medium), color = RizxTheme.colors.muted, textAlign = TextAlign.Center)
    }
}
