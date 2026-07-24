package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.ui.components.RizxToggle
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.settings.AppLanguage
import fm.rizx.player.ui.settings.PreferencesViewModel
import fm.rizx.player.ui.settings.currentAppLanguage
import fm.rizx.player.ui.settings.setAppLanguage
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.ResponsiveContent
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.pagePadding
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

@Composable
fun PreferencesScreen(
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    onOpenSources: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: PreferencesViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val context = LocalContext.current
    val crossfade by vm.crossfade.collectAsStateWithLifecycle()
    val gapless by vm.gapless.collectAsStateWithLifecycle()
    val normalize by vm.normalize.collectAsStateWithLifecycle()
    val hiRes by vm.hiRes.collectAsStateWithLifecycle()
    val audioOutputLabel by vm.audioOutputLabel.collectAsStateWithLifecycle()
    val dataSaver by vm.dataSaver.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val cacheLimit by vm.audioCacheLimitLabel.collectAsStateWithLifecycle()

    // Read once per composition; selecting a language recreates the activity, so this re-reads fresh.
    val currentLang = currentAppLanguage(context)
    var languageDialogOpen by remember { mutableStateOf(false) }
    var themeDialogOpen by remember { mutableStateOf(false) }

    // stringResource can't be called inside the non-composable buildString lambda, so resolve first.
    val hiresBest = stringResource(R.string.pref_hires_best)
    val hiresApplies = stringResource(R.string.pref_hires_applies)

    ResponsiveContent(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pagePadding()),
    ) {
        Text(stringResource(R.string.settings_title), style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

        SectionLabel(stringResource(R.string.settings_playback))
        SettingRow(stringResource(R.string.pref_plugins), stringResource(R.string.pref_plugins_v), onClick = onOpenSources)
        SettingRow(stringResource(R.string.pref_equalizer), stringResource(R.string.pref_equalizer_v), onClick = onOpenEqualizer)
        // Audio quality is now automatic (max by default; lower only on data saver + cellular or a weak
        // signal), so it's no longer a manual row. Crossfade/Gapless/Normalize persist and take effect.
        ToggleRow(stringResource(R.string.pref_crossfade), crossfade) { vm.setCrossfade(!crossfade) }
        ToggleRow(stringResource(R.string.pref_gapless), gapless) { vm.setGapless(!gapless) }
        ToggleRow(stringResource(R.string.pref_normalize), normalize) { vm.setNormalize(!normalize) }
        ToggleRowDetail(
            title = stringResource(R.string.pref_hires),
            caption = buildString {
                append(hiresBest); append("\n")
                if (audioOutputLabel.isNotEmpty()) { append(audioOutputLabel); append(" · ") }
                append(hiresApplies)
            },
            checked = hiRes,
        ) { vm.setHiRes(!hiRes) }

        SectionLabel(stringResource(R.string.settings_appearance))
        // A three-way picker (System / Light / Dark), like the language row — System (default) follows the
        // device's dark-mode setting.
        SettingRow(
            title = stringResource(R.string.pref_theme),
            value = stringResource(themeModeLabel(themeMode)),
            onClick = { themeDialogOpen = true },
        )

        SectionLabel(stringResource(R.string.settings_language_section))
        // Tapping opens a picker; the OS owns the per-app locale, so the choice persists and also shows under
        // Android's own per-app Language page. "System default" follows the device (English fallback).
        SettingRow(
            title = stringResource(R.string.pref_language),
            value = if (currentLang == AppLanguage.SYSTEM) stringResource(R.string.language_system) else currentLang.endonym,
            onClick = { languageDialogOpen = true },
        )

        SectionLabel(stringResource(R.string.settings_data_storage))
        ToggleRow(stringResource(R.string.pref_data_saver), dataSaver) { vm.setDataSaver(!dataSaver) }
        // Tapping cycles the limit rather than opening a dialog: four values, and this row already sits
        // next to "Clear cache", which is where someone worried about space is looking. The explanation
        // is a caption, not the value — as the value it swallowed the whole row.
        SettingRow(
            title = stringResource(R.string.pref_offline_cache),
            value = cacheLimit,
            caption = stringResource(R.string.pref_offline_cache_cap),
            onClick = vm::cycleAudioCacheLimit,
        )
        SettingRow(stringResource(R.string.pref_clear_cache), cacheSize, onClick = vm::clearCache)

        SectionLabel(stringResource(R.string.settings_about_section))
        SettingRow(stringResource(R.string.pref_about), stringResource(R.string.pref_about_v), onClick = onOpenAbout)

        // Ends above the floating chrome instead of behind it (measured, see LocalBottomInset).
        Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
    }

    if (languageDialogOpen) {
        LanguageDialog(
            current = currentLang,
            onSelect = { lang -> setAppLanguage(context, lang); languageDialogOpen = false },
            onDismiss = { languageDialogOpen = false },
        )
    }
    if (themeDialogOpen) {
        ThemeDialog(
            current = themeMode,
            onSelect = { mode -> onSetThemeMode(mode); themeDialogOpen = false },
            onDismiss = { themeDialogOpen = false },
        )
    }
}

/** The @StringRes label for a [ThemeMode], shown in the row and the picker. */
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/** Theme picker: System (follows the device) · Light · Dark. Mirrors [LanguageDialog]'s brutalist style. */
@Composable
private fun ThemeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.elev)
                .border(1.5.dp, c.hardLine)
                .padding(bottom = 8.dp),
        ) {
            Text(
                stringResource(R.string.pref_theme),
                style = sg(20, FontWeight.Bold, -0.01f),
                color = c.text,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = { onSelect(mode) })
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(themeModeLabel(mode)), style = mr(15, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                    if (mode == current) Icon(RizxIcons.Check, "Selected", tint = c.redAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * The language picker: System default (follows the device) + the four shipped languages, each shown in its
 * own name (endonym). Selecting one applies it immediately via the OS per-app locale, which recreates the
 * activity so the whole UI re-reads in that language.
 */
@Composable
private fun LanguageDialog(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.elev)
                .border(1.5.dp, c.hardLine)
                .padding(bottom = 8.dp),
        ) {
            Text(
                stringResource(R.string.pref_language),
                style = sg(20, FontWeight.Bold, -0.01f),
                color = c.text,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            AppLanguage.entries.forEach { lang ->
                val label = if (lang == AppLanguage.SYSTEM) stringResource(R.string.language_system) else lang.endonym
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = { onSelect(lang) })
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = mr(15, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                    if (lang == current) Icon(RizxIcons.Check, "Selected", tint = c.redAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
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
