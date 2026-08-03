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
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.CanvasBlockReason
import fm.rizx.player.domain.model.CanvasDiagnostics
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.ui.components.CaptionedOptionDialog
import fm.rizx.player.ui.components.RizxToggle
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
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
    val autoEq by vm.autoEq.collectAsStateWithLifecycle()
    val audioQuality by vm.audioQuality.collectAsStateWithLifecycle()
    val losslessAvailable by vm.losslessAvailable.collectAsStateWithLifecycle()
    // In force right now, from either switch — separate from `dataSaver`, which is only Rizx's own.
    val savingActive by vm.savingActive.collectAsStateWithLifecycle()
    val losslessWifiOnly by vm.losslessWifiOnly.collectAsStateWithLifecycle()
    val downloadFormat by vm.downloadFormat.collectAsStateWithLifecycle()
    val saveToPhone by vm.saveToPhone.collectAsStateWithLifecycle()
    val showTechnicalFormat by vm.showTechnicalFormat.collectAsStateWithLifecycle()
    val audioOutputLabel by vm.audioOutputLabel.collectAsStateWithLifecycle()
    val dataSaver by vm.dataSaver.collectAsStateWithLifecycle()
    val regionalRecs by vm.regionalRecs.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val cacheLimit by vm.audioCacheLimitLabel.collectAsStateWithLifecycle()

    // Read once per composition; selecting a language recreates the activity, so this re-reads fresh.
    val currentLang = currentAppLanguage(context)
    val radioAlgorithm by vm.radioAlgorithm.collectAsStateWithLifecycle()
    val lyricsQuality by vm.lyricsQuality.collectAsStateWithLifecycle()
    val canvasEnabled by vm.canvasEnabled.collectAsStateWithLifecycle()
    val canvasNetwork by vm.canvasNetwork.collectAsStateWithLifecycle()
    val canvasOnBatterySaver by vm.canvasOnBatterySaver.collectAsStateWithLifecycle()
    val canvasQuality by vm.canvasQuality.collectAsStateWithLifecycle()
    val canvasApple by vm.canvasApple.collectAsStateWithLifecycle()
    val canvasYoutube by vm.canvasYoutube.collectAsStateWithLifecycle()
    val canvasDiagnostics by vm.canvasDiagnostics.collectAsStateWithLifecycle()
    var languageDialogOpen by remember { mutableStateOf(false) }
    var themeDialogOpen by remember { mutableStateOf(false) }
    var radioDialogOpen by remember { mutableStateOf(false) }
    var lyricsQualityDialogOpen by remember { mutableStateOf(false) }
    var canvasDialogOpen by remember { mutableStateOf(false) }
    var audioQualityDialogOpen by remember { mutableStateOf(false) }
    var downloadFormatDialogOpen by remember { mutableStateOf(false) }
    val feedProvider by vm.feedProvider.collectAsStateWithLifecycle()
    val feedSources by vm.feedSources.collectAsStateWithLifecycle()
    var feedDialogOpen by remember { mutableStateOf(false) }
    // "All combined" first: it is the option that isn't a provider, and building the list here keeps
    // the ViewModel free of string resources.
    val feedOptions = listOf(
        FeedOption(
            id = SettingsRepositoryImpl.FEED_PROVIDER_ALL,
            name = stringResource(R.string.feed_provider_all),
            caption = stringResource(R.string.feed_provider_all_caption),
        ),
    ) + feedSources.map { source ->
        FeedOption(
            id = source.id,
            name = source.name,
            caption = stringResource(R.string.feed_provider_only_caption, source.name),
        )
    }

    // stringResource can't be called inside the non-composable buildString lambda, so resolve first.
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
        // Sits right under the equalizer row it takes over, and carries a caption because "automatic
        // equalizer" doesn't say *what* it automates — the genre, and then the song itself.
        ToggleRowDetail(
            title = stringResource(R.string.pref_auto_eq),
            caption = stringResource(R.string.pref_auto_eq_caption),
            checked = autoEq,
        ) { vm.setAutoEq(!autoEq) }
        // Audio quality is now automatic (max by default; lower only on data saver + cellular or a weak
        // signal), so it's no longer a manual row. Crossfade/Gapless/Normalize persist and take effect.
        ToggleRow(stringResource(R.string.pref_crossfade), crossfade) { vm.setCrossfade(!crossfade) }
        ToggleRow(stringResource(R.string.pref_gapless), gapless) { vm.setGapless(!gapless) }
        ToggleRow(stringResource(R.string.pref_normalize), normalize) { vm.setNormalize(!normalize) }
        // One row rather than five: the mode, the network rule, the download rule and the readout are all
        // facets of the same decision, and the last three are meaningless while the mode is Standard.
        SettingRow(
            title = stringResource(R.string.pref_audio_quality),
            // What is *in force*, which while saving is not what is stored. The dialog still ticks the
            // stored choice — both truths are shown, and neither is overwritten.
            value = stringResource(
                if (savingActive) R.string.pref_audio_quality_standard else audioQualityLabel(audioQuality),
            ),
            caption = if (savingActive) {
                stringResource(R.string.pref_forced_by_data_saver)
            } else {
                buildString {
                    if (audioOutputLabel.isNotEmpty()) { append(audioOutputLabel); append(" · ") }
                    append(hiresApplies)
                }
            },
            onClick = {
                // A plugin can have been installed since this screen was last drawn, which is exactly
                // what turns the Lossless option from unavailable into available.
                vm.refreshLosslessAvailability()
                audioQualityDialogOpen = true
            },
        )
        // What a download saves. Its own row rather than a toggle inside the audio dialog (where the old
        // FLAC switch lived) because it now names four different answers, and the one it replaces is the
        // migration's job to carry over.
        SettingRow(
            title = stringResource(R.string.pref_download_format),
            value = stringResource(downloadFormatLabel(downloadFormat)),
            caption = stringResource(downloadFormatCaption(downloadFormat)),
            onClick = { downloadFormatDialogOpen = true },
        )
        // Right under the format, because both answer "what do I end up with?". A download is otherwise
        // app-private: it plays offline but no file manager and no other player can see it.
        ToggleRowDetail(
            title = stringResource(R.string.pref_save_to_phone),
            caption = stringResource(R.string.pref_save_to_phone_caption),
            checked = saveToPhone == true,
        ) { vm.setSaveToPhone(saveToPhone != true) }

        SectionLabel(stringResource(R.string.settings_appearance))
        // A three-way picker (System / Light / Dark), like the language row — System (default) follows the
        // device's dark-mode setting.
        SettingRow(
            title = stringResource(R.string.pref_theme),
            value = stringResource(themeModeLabel(themeMode)),
            onClick = { themeDialogOpen = true },
        )
        // The karaoke sweep is the one screen that asks for a frame every frame. Automatic steps itself
        // down on a phone that can't afford it; this row is the manual override in both directions.
        SettingRow(
            title = stringResource(R.string.pref_lyrics_quality),
            value = stringResource(lyricsQualityLabel(lyricsQuality)),
            caption = stringResource(lyricsQualityCaption(lyricsQuality)),
            onClick = { lyricsQualityDialogOpen = true },
        )
        // One row rather than seven: the sources, the quality, the network rule, the battery rule and the
        // diagnostics all belong to the same decision, and every one of them is meaningless while it is
        // off.
        SettingRow(
            title = stringResource(R.string.pref_canvas),
            value = stringResource(
                if (canvasEnabled && !savingActive) R.string.pref_canvas_on else R.string.pref_canvas_off,
            ),
            caption = when {
                // Switched on but not running: say which of the two it is, rather than showing "On"
                // over a cover that never animates.
                savingActive -> stringResource(R.string.pref_paused_by_data_saver)
                canvasEnabled -> stringResource(canvasNetworkLabel(canvasNetwork))
                else -> stringResource(R.string.pref_canvas_enable_caption)
            },
            onClick = { canvasDialogOpen = true },
        )

        SectionLabel(stringResource(R.string.settings_language_section))
        // Tapping opens a picker; the OS owns the per-app locale, so the choice persists and also shows under
        // Android's own per-app Language page. "System default" follows the device (English fallback).
        SettingRow(
            title = stringResource(R.string.pref_language),
            value = if (currentLang == AppLanguage.SYSTEM) stringResource(R.string.language_system) else currentLang.endonym,
            onClick = { languageDialogOpen = true },
        )

        SectionLabel(stringResource(R.string.settings_recs_section))
        // Which engine fills "next" after playing one song from the feed or from search. Both are real
        // recommendation systems, they just read different things — YT Music reads the *song*, Deezer
        // reads its *artist* — so it's a taste choice, not a quality one.
        SettingRow(
            title = stringResource(R.string.pref_radio_algorithm),
            value = stringResource(radioAlgorithmLabel(radioAlgorithm)),
            caption = stringResource(radioAlgorithmCaption(radioAlgorithm)),
            onClick = { radioDialogOpen = true },
        )
        // Which platform's charts fill Home. The options come from the registry, so a dashboard
        // plugin appears here on its own.
        SettingRow(
            title = stringResource(R.string.pref_feed_provider),
            value = feedOptions.firstOrNull { it.id == feedProvider }?.name
                ?: stringResource(R.string.feed_provider_all),
            caption = stringResource(R.string.pref_feed_provider_caption),
            onClick = { vm.refreshFeedSources(); feedDialogOpen = true },
        )
        ToggleRowDetail(
            title = stringResource(R.string.settings_regional_recs),
            caption = when (regionalRecs) {
                true -> vm.regionCountry?.let { stringResource(R.string.settings_regional_recs_on, it) }
                    ?: stringResource(R.string.settings_regional_recs_off)
                false -> stringResource(R.string.settings_regional_recs_off)
                null -> stringResource(R.string.settings_regional_recs_unset)
            },
            checked = regionalRecs == true,
        ) { vm.setRegionalRecs(regionalRecs != true) }

        SectionLabel(stringResource(R.string.settings_data_storage))
        // A caption now, because the switch finally does enough to be worth listing. When Android's own
        // Data saver is what turned this on, say so — otherwise the row reads as off while everything
        // behaves as though it were on.
        ToggleRowDetail(
            title = stringResource(R.string.pref_data_saver),
            caption = if (savingActive && !dataSaver) {
                stringResource(R.string.pref_data_saver_system)
            } else {
                stringResource(R.string.pref_data_saver_caption)
            },
            checked = dataSaver,
        ) { vm.setDataSaver(!dataSaver) }
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
    if (radioDialogOpen) {
        RadioAlgorithmDialog(
            current = radioAlgorithm,
            onSelect = { mode -> vm.setRadioAlgorithm(mode); radioDialogOpen = false },
            onDismiss = { radioDialogOpen = false },
        )
    }
    if (lyricsQualityDialogOpen) {
        LyricsQualityDialog(
            current = lyricsQuality,
            onSelect = { q -> vm.setLyricsQuality(q); lyricsQualityDialogOpen = false },
            onDismiss = { lyricsQualityDialogOpen = false },
        )
    }
    if (audioQualityDialogOpen) {
        AudioQualityDialog(
            mode = audioQuality,
            losslessAvailable = losslessAvailable,
            wifiOnly = losslessWifiOnly,
            showTechnical = showTechnicalFormat,
            onSetMode = vm::setAudioQuality,
            onSetWifiOnly = vm::setLosslessWifiOnly,
            onSetShowTechnical = vm::setShowTechnicalFormat,
            onDismiss = { audioQualityDialogOpen = false },
        )
    }
    if (downloadFormatDialogOpen) {
        CaptionedOptionDialog(
            title = stringResource(R.string.pref_download_format),
            options = DownloadFormat.entries,
            current = downloadFormat,
            label = { stringResource(downloadFormatLabel(it)) },
            caption = { stringResource(downloadFormatCaption(it)) },
            onSelect = { format -> vm.setDownloadFormat(format); downloadFormatDialogOpen = false },
            onDismiss = { downloadFormatDialogOpen = false },
        )
    }

    if (canvasDialogOpen) {
        CanvasDialog(
            enabled = canvasEnabled,
            network = canvasNetwork,
            quality = canvasQuality,
            onBatterySaver = canvasOnBatterySaver,
            appleEnabled = canvasApple,
            youtubeEnabled = canvasYoutube,
            diagnostics = canvasDiagnostics,
            onSetEnabled = vm::setCanvasEnabled,
            onSetNetwork = vm::setCanvasNetwork,
            onSetQuality = vm::setCanvasQuality,
            onSetBatterySaver = vm::setCanvasOnBatterySaver,
            onSetApple = vm::setCanvasApple,
            onSetYoutube = vm::setCanvasYoutube,
            onDismiss = { canvasDialogOpen = false },
        )
    }
    if (feedDialogOpen) {
        CaptionedOptionDialog(
            title = stringResource(R.string.pref_feed_provider),
            options = feedOptions,
            current = feedOptions.firstOrNull { it.id == feedProvider } ?: feedOptions.first(),
            label = { it.name },
            caption = { it.caption },
            onSelect = { option -> vm.setFeedProvider(option.id); feedDialogOpen = false },
            onDismiss = { feedDialogOpen = false },
        )
    }
}

/** One row of the feed-source picker: a registered dashboard provider, or the "all combined" option. */
private data class FeedOption(val id: String, val name: String, val caption: String)

/** The @StringRes name of a [DownloadFormat]. */
private fun downloadFormatLabel(format: DownloadFormat): Int = when (format) {
    DownloadFormat.ORIGINAL -> R.string.download_format_original
    DownloadFormat.OPUS -> R.string.download_format_opus
    DownloadFormat.MP3 -> R.string.download_format_mp3
    DownloadFormat.FLAC -> R.string.download_format_flac
}

/** One honest line per format — what it saves and what it costs, tradeoffs included. */
private fun downloadFormatCaption(format: DownloadFormat): Int = when (format) {
    DownloadFormat.ORIGINAL -> R.string.download_format_original_caption
    DownloadFormat.OPUS -> R.string.download_format_opus_caption
    DownloadFormat.MP3 -> R.string.download_format_mp3_caption
    DownloadFormat.FLAC -> R.string.download_format_flac_caption
}

/** The @StringRes name of a [RadioMode], as the user thinks of it — by the service, not the mechanism. */
private fun radioAlgorithmLabel(mode: RadioMode): Int = when (mode) {
    RadioMode.YOUTUBE -> R.string.radio_algorithm_youtube
    RadioMode.ARTIST -> R.string.radio_algorithm_deezer
    RadioMode.APPLEMUSIC -> R.string.radio_algorithm_apple
    RadioMode.SOUNDCLOUD -> R.string.radio_algorithm_soundcloud
}

/** One line on what each engine actually does, so the choice isn't a row of opaque brand names. */
private fun radioAlgorithmCaption(mode: RadioMode): Int = when (mode) {
    RadioMode.YOUTUBE -> R.string.radio_algorithm_youtube_caption
    RadioMode.ARTIST -> R.string.radio_algorithm_deezer_caption
    RadioMode.APPLEMUSIC -> R.string.radio_algorithm_apple_caption
    RadioMode.SOUNDCLOUD -> R.string.radio_algorithm_soundcloud_caption
}

/**
 * Picks what plays next after one song. Same brutalist picker as [ThemeDialog], but each option
 * carries its caption — the difference between the two engines is the whole point of the choice.
 */
@Composable
private fun RadioAlgorithmDialog(
    current: RadioMode,
    onSelect: (RadioMode) -> Unit,
    onDismiss: () -> Unit,
) = CaptionedOptionDialog(
    title = stringResource(R.string.pref_radio_algorithm),
    options = RadioMode.entries,
    current = current,
    label = { stringResource(radioAlgorithmLabel(it)) },
    caption = { stringResource(radioAlgorithmCaption(it)) },
    onSelect = onSelect,
    onDismiss = onDismiss,
)

/** The @StringRes name of a [LyricsVisualQuality]. */
private fun lyricsQualityLabel(quality: LyricsVisualQuality): Int = when (quality) {
    LyricsVisualQuality.AUTOMATIC -> R.string.lyrics_quality_automatic
    LyricsVisualQuality.HIGH -> R.string.lyrics_quality_high
    LyricsVisualQuality.BATTERY_SAVER -> R.string.lyrics_quality_saver
}

/** What each level actually costs, since "High" on its own says nothing about the trade. */
private fun lyricsQualityCaption(quality: LyricsVisualQuality): Int = when (quality) {
    LyricsVisualQuality.AUTOMATIC -> R.string.lyrics_quality_automatic_caption
    LyricsVisualQuality.HIGH -> R.string.lyrics_quality_high_caption
    LyricsVisualQuality.BATTERY_SAVER -> R.string.lyrics_quality_saver_caption
}

@Composable
private fun LyricsQualityDialog(
    current: LyricsVisualQuality,
    onSelect: (LyricsVisualQuality) -> Unit,
    onDismiss: () -> Unit,
) = CaptionedOptionDialog(
    title = stringResource(R.string.pref_lyrics_quality),
    options = LyricsVisualQuality.entries,
    current = current,
    label = { stringResource(lyricsQualityLabel(it)) },
    caption = { stringResource(lyricsQualityCaption(it)) },
    onSelect = onSelect,
    onDismiss = onDismiss,
)

/** The @StringRes name of a [CanvasNetworkPolicy]. */
private fun canvasNetworkLabel(policy: CanvasNetworkPolicy): Int = when (policy) {
    CanvasNetworkPolicy.UNMETERED_ONLY -> R.string.canvas_network_unmetered
    CanvasNetworkPolicy.ANY -> R.string.canvas_network_any
}

private fun canvasNetworkCaption(policy: CanvasNetworkPolicy): Int = when (policy) {
    CanvasNetworkPolicy.UNMETERED_ONLY -> R.string.canvas_network_unmetered_caption
    CanvasNetworkPolicy.ANY -> R.string.canvas_network_any_caption
}

private fun canvasQualityLabel(quality: CanvasQuality): Int = when (quality) {
    CanvasQuality.DATA_SAVER -> R.string.canvas_quality_saver
    CanvasQuality.AUTO -> R.string.canvas_quality_auto
    CanvasQuality.HIGH -> R.string.canvas_quality_high
}

private fun canvasQualityCaption(quality: CanvasQuality): Int = when (quality) {
    CanvasQuality.DATA_SAVER -> R.string.canvas_quality_saver_caption
    CanvasQuality.AUTO -> R.string.canvas_quality_auto_caption
    CanvasQuality.HIGH -> R.string.canvas_quality_high_caption
}

/** Why there is no canvas, in a sentence. Every one of these is a normal outcome, not an error. */
private fun canvasReasonLabel(reason: CanvasBlockReason): Int = when (reason) {
    CanvasBlockReason.DISABLED -> R.string.canvas_reason_disabled
    CanvasBlockReason.DATA_SAVER -> R.string.canvas_reason_data_saver
    CanvasBlockReason.METERED -> R.string.canvas_reason_metered
    CanvasBlockReason.BATTERY_SAVER -> R.string.canvas_reason_battery
    CanvasBlockReason.WEAK_SIGNAL -> R.string.canvas_reason_signal
    CanvasBlockReason.NO_CANDIDATE -> R.string.canvas_reason_no_candidate
    CanvasBlockReason.REJECTED_BY_MATCHER -> R.string.canvas_reason_rejected
    CanvasBlockReason.PROVIDER_ERROR -> R.string.canvas_reason_error
}

/**
 * Everything about animated covers, in one sheet.
 *
 * A dialog rather than seven rows in Settings: the sources, the quality, the network rule, the battery
 * rule and the diagnostics are all meaningless while the feature is off, and a screen full of dead rows
 * is worse than one live one. Everything below the master toggle is hidden until it is on, for the same
 * reason.
 */
@Composable
private fun AudioQualityDialog(
    mode: AudioQualityMode,
    losslessAvailable: Boolean,
    wifiOnly: Boolean,
    showTechnical: Boolean,
    onSetMode: (AudioQualityMode) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetShowTechnical: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.elev)
                .border(1.5.dp, c.hardLine)
                .padding(bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.pref_audio_quality),
                style = sg(20, FontWeight.Bold, -0.01f),
                color = c.text,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )

            AudioQualityMode.entries.forEach { option ->
                // Lossless is shown but not selectable with no index plugin installed. Greyed with the
                // reason rather than hidden, because unlike the desktop-only plugins this is one step
                // away from working — and an option that silently isn't there can't be looked for.
                val selectable = option != AudioQualityMode.LOSSLESS_PREFERRED || losslessAvailable
                DialogOptionRow(
                    label = stringResource(audioQualityLabel(option)),
                    caption = stringResource(
                        if (selectable) audioQualityCaption(option) else R.string.pref_lossless_unavailable,
                    ),
                    selected = option == mode,
                    enabled = selectable,
                    onClick = { if (selectable) onSetMode(option) },
                )
            }

            if (mode == AudioQualityMode.LOSSLESS_PREFERRED) {
                DialogSectionLabel(stringResource(R.string.pref_lossless_source))
                // Verbatim, and it is the honest boundary of the whole feature: the container and the
                // duration are measured, the origin and the licence cannot be.
                Text(
                    stringResource(R.string.pref_lossless_warning),
                    style = mr(12, FontWeight.Normal),
                    color = c.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                DialogToggleRow(
                    title = stringResource(R.string.pref_lossless_wifi_only),
                    caption = stringResource(R.string.pref_lossless_wifi_only_caption),
                    checked = wifiOnly,
                    onToggle = { onSetWifiOnly(!wifiOnly) },
                )
                // The old "download the FLAC" toggle lived here; it grew into the Download format row
                // outside this dialog, where it names all four answers instead of one.
            }

            DialogToggleRow(
                title = stringResource(R.string.pref_show_technical_format),
                caption = stringResource(R.string.pref_show_technical_format_caption),
                checked = showTechnical,
                onToggle = { onSetShowTechnical(!showTechnical) },
            )
        }
    }
}

private fun audioQualityLabel(mode: AudioQualityMode): Int = when (mode) {
    AudioQualityMode.STANDARD -> R.string.pref_audio_quality_standard
    AudioQualityMode.BEST_AVAILABLE -> R.string.pref_audio_quality_best
    AudioQualityMode.LOSSLESS_PREFERRED -> R.string.pref_audio_quality_lossless
}

private fun audioQualityCaption(mode: AudioQualityMode): Int = when (mode) {
    AudioQualityMode.STANDARD -> R.string.pref_audio_quality_standard_caption
    AudioQualityMode.BEST_AVAILABLE -> R.string.pref_audio_quality_best_caption
    AudioQualityMode.LOSSLESS_PREFERRED -> R.string.pref_audio_quality_lossless_caption
}

@Composable
private fun CanvasDialog(
    enabled: Boolean,
    network: CanvasNetworkPolicy,
    quality: CanvasQuality,
    onBatterySaver: Boolean,
    appleEnabled: Boolean,
    youtubeEnabled: Boolean,
    diagnostics: CanvasDiagnostics,
    onSetEnabled: (Boolean) -> Unit,
    onSetNetwork: (CanvasNetworkPolicy) -> Unit,
    onSetQuality: (CanvasQuality) -> Unit,
    onSetBatterySaver: (Boolean) -> Unit,
    onSetApple: (Boolean) -> Unit,
    onSetYoutube: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.elev)
                .border(1.5.dp, c.hardLine)
                .padding(bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.pref_canvas),
                style = sg(20, FontWeight.Bold, -0.01f),
                color = c.text,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )
            DialogToggleRow(
                title = stringResource(R.string.pref_canvas_enable),
                caption = stringResource(R.string.pref_canvas_enable_caption),
                checked = enabled,
                onToggle = { onSetEnabled(!enabled) },
            )
            if (enabled) {
                DialogSectionLabel(stringResource(R.string.pref_canvas_sources))
                // Two switches, because the two sources fail differently. Apple either has this album's
                // loop or it hasn't; YouTube is a search, and a search is the thing that can be wrong.
                DialogToggleRow(
                    title = stringResource(R.string.pref_canvas_apple),
                    caption = stringResource(R.string.pref_canvas_apple_caption),
                    checked = appleEnabled,
                    onToggle = { onSetApple(!appleEnabled) },
                )
                DialogToggleRow(
                    title = stringResource(R.string.pref_canvas_youtube),
                    caption = stringResource(R.string.pref_canvas_youtube_caption),
                    checked = youtubeEnabled,
                    onToggle = { onSetYoutube(!youtubeEnabled) },
                )

                DialogSectionLabel(stringResource(R.string.pref_canvas_quality))
                CanvasQuality.entries.forEach { option ->
                    DialogOptionRow(
                        label = stringResource(canvasQualityLabel(option)),
                        caption = stringResource(canvasQualityCaption(option)),
                        selected = option == quality,
                        onClick = { onSetQuality(option) },
                    )
                }

                DialogSectionLabel(stringResource(R.string.pref_canvas_network))
                CanvasNetworkPolicy.entries.forEach { option ->
                    DialogOptionRow(
                        label = stringResource(canvasNetworkLabel(option)),
                        caption = stringResource(canvasNetworkCaption(option)),
                        selected = option == network,
                        onClick = { onSetNetwork(option) },
                    )
                }
                DialogToggleRow(
                    title = stringResource(R.string.pref_canvas_battery),
                    caption = stringResource(R.string.pref_canvas_battery_caption),
                    checked = onBatterySaver,
                    onToggle = { onSetBatterySaver(!onBatterySaver) },
                )
            }

            DialogSectionLabel(stringResource(R.string.canvas_diagnostics))
            Text(
                canvasDiagnosticsText(diagnostics),
                style = code(12),
                color = c.muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/**
 * The last lookup, as one line of monospace.
 *
 * Deliberately **never** the URL: a resolved googlevideo link carries a signed token, and a diagnostics
 * panel is exactly the kind of thing people screenshot into a bug report.
 *
 * The size and frame rate come from the *player*, not the lookup, and are absent until a frame has been
 * decoded — which is the only moment either is actually known.
 */
@Composable
private fun canvasDiagnosticsText(d: CanvasDiagnostics): String {
    val blocked = d.blockedBy?.let { stringResource(canvasReasonLabel(it)) }
    val cacheWord = stringResource(if (d.cacheHit) R.string.canvas_diag_cached else R.string.canvas_diag_fresh)
    val none = stringResource(R.string.canvas_diag_none)
    val parts = buildList {
        d.providerId?.let { add(it) }
        d.score?.let { add("$it/100") }
        d.aspect?.let { add(it.name.lowercase()) }
        if (d.providerId != null) add(cacheWord)
        if (d.resolveMs > 0L) add("${d.resolveMs} ms")
        if (d.width != null && d.height != null) add("${d.width}×${d.height}")
        d.frameRate?.let { add("${it.toInt()} fps") }
        d.firstFrameMs?.let { add("1st frame ${it} ms") }
        blocked?.let { add(it) }
        d.error?.let { add(it) }
    }
    return if (parts.isEmpty()) none else parts.joinToString(" · ")
}

/** A small heading inside a dialog — the same role [SectionLabel] plays in the list. */
@Composable
private fun DialogSectionLabel(text: String) {
    Text(
        text,
        style = mr(12, FontWeight.SemiBold),
        color = RizxTheme.colors.muted,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

/**
 * A pick-one row inside a dialog, ticked when it is the current choice.
 *
 * [enabled] false dims it and drops the press feedback rather than removing the row: an option that is
 * one installed plugin away from working should be visible, with its caption saying what is missing.
 */
@Composable
private fun DialogOptionRow(
    label: String,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = mr(15, FontWeight.SemiBold),
                color = if (enabled) c.text else c.muted,
            )
            Text(caption, style = mr(12, FontWeight.Normal), color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (selected) {
            Icon(RizxIcons.Check, null, tint = c.redAccent, modifier = Modifier.padding(start = 12.dp).size(20.dp))
        }
    }
}

/** A toggle row sized for a dialog rather than the Settings list. */
@Composable
private fun DialogToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = mr(15, FontWeight.SemiBold), color = c.text)
            Text(caption, style = mr(12, FontWeight.Normal), color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        RizxToggle(checked = checked, onToggle = onToggle, modifier = Modifier.padding(start = 12.dp))
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
