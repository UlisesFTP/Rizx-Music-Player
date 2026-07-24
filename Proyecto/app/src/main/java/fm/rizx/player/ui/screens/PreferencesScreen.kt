package fm.rizx.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.ui.components.RizxToggle
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.settings.PreferencesViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.ResponsiveContent
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.pagePadding
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

@Composable
fun PreferencesScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: PreferencesViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val crossfade by vm.crossfade.collectAsStateWithLifecycle()
    val gapless by vm.gapless.collectAsStateWithLifecycle()
    val normalize by vm.normalize.collectAsStateWithLifecycle()
    val hiRes by vm.hiRes.collectAsStateWithLifecycle()
    val audioOutputLabel by vm.audioOutputLabel.collectAsStateWithLifecycle()
    val dataSaver by vm.dataSaver.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val cacheLimit by vm.audioCacheLimitLabel.collectAsStateWithLifecycle()

    ResponsiveContent(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pagePadding()),
    ) {
        Text("Settings", style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

        SectionLabel("Playback")
        SettingRow("Plugins & sources", "Manage", onClick = onOpenSources)
        SettingRow("Equalizer", "Presets · bands", onClick = onOpenEqualizer)
        // Audio quality is now automatic (max by default; lower only on data saver + cellular or a weak
        // signal), so it's no longer a manual row. Crossfade/Gapless/Normalize persist and take effect.
        ToggleRow("Crossfade", crossfade) { vm.setCrossfade(!crossfade) }
        ToggleRow("Gapless playback", gapless) { vm.setGapless(!gapless) }
        ToggleRow("Normalize volume", normalize) { vm.setNormalize(!normalize) }
        ToggleRowDetail(
            title = "Hi-Res output",
            caption = buildString {
                append("Best with local lossless files\n")
                if (audioOutputLabel.isNotEmpty()) { append(audioOutputLabel); append(" · ") }
                append("applies on next playback")
            },
            checked = hiRes,
        ) { vm.setHiRes(!hiRes) }

        SectionLabel("Appearance")
        SettingRow("Theme", if (isDark) "Dark" else "Light", onClick = onToggleTheme)

        SectionLabel("Data & storage")
        ToggleRow("Data saver", dataSaver) { vm.setDataSaver(!dataSaver) }
        // Tapping cycles the limit rather than opening a dialog: four values, and this row already sits
        // next to "Clear cache", which is where someone worried about space is looking. The explanation
        // is a caption, not the value — as the value it swallowed the whole row.
        SettingRow(
            title = "Offline cache",
            value = cacheLimit,
            caption = "Keeps played songs for offline replay",
            onClick = vm::cycleAudioCacheLimit,
        )
        SettingRow("Clear cache", cacheSize, onClick = vm::clearCache)

        SectionLabel("About")
        SettingRow("About Rizx", "License · sources", onClick = onOpenAbout)

        // Ends above the floating chrome instead of behind it (measured, see LocalBottomInset).
        Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = code(11, FontWeight.Bold),
        color = RizxTheme.colors.muted,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/**
 * A tappable settings row: title (with optional explanatory [caption]) on the left, short [value] on the
 * right, chevron last.
 *
 * **The value has to be width-bounded.** Compose measures unweighted children first against the *whole*
 * row, so an unbounded value took everything and left the title and the chevron zero width — the
 * "Offline cache" row rendered as a floating sentence with no title and no chevron at all. Capping it at
 * a fraction of the screen lets a short value take only what it needs while the weighted title keeps the
 * rest; a weight on the value instead would hand it a fixed half whether it needed it or not, and split
 * "Offline cache" across two lines for nothing.
 *
 * Anything longer than a couple of words belongs in [caption], not [value].
 */
@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    caption: String? = null,
    onClick: () -> Unit = {},
) {
    val c = RizxTheme.colors
    val valueMax = (LocalConfiguration.current.screenWidthDp * 0.4f).dp
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = mr(14, FontWeight.SemiBold), color = c.text)
            if (!caption.isNullOrBlank()) {
                Text(caption, style = mr(11, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (value != null) {
            Text(
                value,
                style = mr(13, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = valueMax),
            )
        }
        Icon(RizxIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onToggle).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = mr(14, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
        RizxToggle(checked = checked, onToggle = onToggle)
    }
}

/** A [ToggleRow] with a muted caption line under the title — used to carry the Hi-Res explainer + the
 *  device's DAC capability, without a second (misleading) chevron row. */
@Composable
private fun ToggleRowDetail(title: String, caption: String, checked: Boolean, onToggle: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onToggle).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = mr(14, FontWeight.SemiBold), color = c.text)
            if (caption.isNotEmpty()) {
                Text(caption, style = mr(11, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
        RizxToggle(checked = checked, onToggle = onToggle)
    }
}
