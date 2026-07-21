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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.ui.components.RizxToggle
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.settings.PreferencesViewModel
import fm.rizx.player.ui.theme.RizxTheme
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
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
        SettingRow("Clear cache", cacheSize, onClick = vm::clearCache)

        SectionLabel("About")
        SettingRow("About Rizx", "License · sources", onClick = onOpenAbout)

        Spacer(Modifier.height(104.dp))
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

@Composable
private fun SettingRow(title: String, value: String?, onClick: () -> Unit = {}) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = mr(14, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = mr(13, FontWeight.Medium), color = c.muted)
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
