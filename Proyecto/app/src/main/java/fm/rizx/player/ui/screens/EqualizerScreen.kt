package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.domain.model.EqPreset
import fm.rizx.player.domain.model.EqualizerState
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.settings.EqualizerViewModel
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

@Composable
fun EqualizerScreen(onBack: () -> Unit, vm: EqualizerViewModel = hiltViewModel()) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                RizxIcons.Back, stringResource(R.string.eq_cd_back), tint = c.text,
                modifier = Modifier.size(26.dp).clickableScale(scale = 0.88f, onClick = onBack),
            )
            Text(stringResource(R.string.eq_title), style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.weight(1f))
            if (state.available) {
                Switch(checked = state.enabled, onCheckedChange = vm::setEnabled)
            }
        }

        if (!state.available) {
            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.eq_unavailable_message),
                    style = mr(14, FontWeight.Medium), color = c.muted,
                )
            }
            return@Column
        }

        Presets(enabled = state.enabled, onPreset = vm::applyPreset)
        Bands(state = state, onBand = vm::setBand)
        Spacer(Modifier.height(60.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(enabled: Boolean, onPreset: (EqPreset) -> Unit) {
    val c = RizxTheme.colors
    Text(stringResource(R.string.eq_section_presets), style = code(11, FontWeight.Bold), color = c.muted, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EqPreset.entries.forEach { preset ->
            Text(
                preset.name.lowercase().replaceFirstChar { it.uppercase() },
                style = mr(13, FontWeight.SemiBold),
                color = if (enabled) c.text else c.muted,
                modifier = Modifier
                    .clip(RectangleShape)
                    .background(c.elev)
                    .border(1.dp, c.line, RectangleShape)
                    .clickableScale(scale = 0.94f, onClick = { onPreset(preset) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Bands(state: EqualizerState, onBand: (Int, Int) -> Unit) {
    val c = RizxTheme.colors
    Text(stringResource(R.string.eq_section_bands), style = code(11, FontWeight.Bold), color = c.muted, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    state.bands.forEach { band ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(freqLabel(band.centerFreqHz), style = mr(13, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                Text("${band.levelMillibel / 100} dB", style = mr(12, FontWeight.Medium), color = c.muted)
            }
            Slider(
                value = band.levelMillibel.toFloat(),
                onValueChange = { onBand(band.index, it.toInt()) },
                valueRange = state.minLevelMillibel.toFloat()..state.maxLevelMillibel.toFloat(),
                enabled = state.enabled,
            )
        }
    }
}

private fun freqLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
