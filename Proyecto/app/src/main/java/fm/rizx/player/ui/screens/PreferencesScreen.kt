package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import fm.rizx.player.domain.model.PlayerLayout
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.ui.components.CaptionedOptionDialog
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.ui.account.AccountViewModel
import fm.rizx.player.ui.components.AccountAvatar
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.DisplayTitle
import fm.rizx.player.ui.components.Editorial
import fm.rizx.player.ui.components.EditorialSearchField
import fm.rizx.player.ui.components.EditorialSelect
import fm.rizx.player.ui.components.EditorialSurface
import fm.rizx.player.ui.components.EmptyBlock
import fm.rizx.player.ui.components.Kicker
import fm.rizx.player.ui.components.Lede
import fm.rizx.player.ui.components.RizxToggle
import fm.rizx.player.ui.components.RowArrow
import fm.rizx.player.ui.components.SegmentedTabs
import fm.rizx.player.ui.components.SignalEyebrow
import fm.rizx.player.ui.components.SurfaceTitle
import fm.rizx.player.ui.components.bottomRule
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.library.ConfirmDialog
import fm.rizx.player.ui.util.ListFilter
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.ui.settings.AppLanguage
import fm.rizx.player.ui.settings.PreferencesViewModel
import fm.rizx.player.ui.settings.currentAppLanguage
import fm.rizx.player.ui.settings.setAppLanguage
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.ResponsiveContent
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.brutalShadow
import fm.rizx.player.ui.theme.isTablet
import fm.rizx.player.ui.theme.pagePadding
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.availableDownloadFormats
import fm.rizx.player.ui.util.rememberSaveToPhonePermission

@Composable
fun PreferencesScreen(
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    onOpenSources: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: PreferencesViewModel = hiltViewModel(),
    accountVm: AccountViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val context = LocalContext.current
    val crossfade by vm.crossfade.collectAsStateWithLifecycle()
    val gapless by vm.gapless.collectAsStateWithLifecycle()
    val normalize by vm.normalize.collectAsStateWithLifecycle()
    val autoEq by vm.autoEq.collectAsStateWithLifecycle()
    val spatialOn by vm.spatialAudioOn.collectAsStateWithLifecycle()
    val avoidDoubleSpatial by vm.avoidDoubleSpatialization.collectAsStateWithLifecycle()
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
    val canvasTidal by vm.canvasTidal.collectAsStateWithLifecycle()
    val canvasYoutube by vm.canvasYoutube.collectAsStateWithLifecycle()
    val canvasDiagnostics by vm.canvasDiagnostics.collectAsStateWithLifecycle()
    val playerLayout by vm.playerLayout.collectAsStateWithLifecycle()
    var languageDialogOpen by remember { mutableStateOf(false) }
    var playerLayoutDialogOpen by remember { mutableStateOf(false) }
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

    var query by rememberSaveable { mutableStateOf("") }
    var clearCacheOpen by remember { mutableStateOf(false) }
    // Hoisted out of the row: turning "Save to the phone" on may first need the legacy storage
    // permission (Android 8–9 only, API < 29), and a permission launcher must be remembered at a
    // stable call position — not inside a row the filter can stop emitting.
    val ensureSavePermission = rememberSaveToPhonePermission()

    // ---- Every row's text, resolved up front -------------------------------------------------
    // The filter has to know what a row *says* before deciding whether to draw it, and
    // `stringResource` only works in a composable scope — so the strings come first and the rows are
    // built from them below. It is verbose, and it is what makes the search possible at all.
    val eqTitle = stringResource(R.string.pref_equalizer)
    val eqValue = stringResource(R.string.pref_equalizer_v)
    val autoEqTitle = stringResource(R.string.pref_auto_eq)
    val autoEqCaption = stringResource(R.string.pref_auto_eq_caption)
    val spatialTitle = stringResource(R.string.spatial_title)
    val spatialCaption = stringResource(R.string.pref_spatial_caption)
    val avoidDoubleTitle = stringResource(R.string.pref_spatial_avoid_double)
    val avoidDoubleCaption = stringResource(R.string.pref_spatial_avoid_double_caption)
    val normalizeTitle = stringResource(R.string.pref_normalize)
    val audioQualityTitle = stringResource(R.string.pref_audio_quality)
    // What is *in force*, which while saving is not what is stored. The dialog still ticks the stored
    // choice — both truths are shown, and neither is overwritten.
    val audioQualityValue = stringResource(
        if (savingActive) R.string.pref_audio_quality_standard else audioQualityLabel(audioQuality),
    )
    val forcedBySaver = stringResource(R.string.pref_forced_by_data_saver)
    val audioQualityCaption = if (savingActive) {
        forcedBySaver
    } else {
        buildString {
            if (audioOutputLabel.isNotEmpty()) { append(audioOutputLabel); append(" · ") }
            append(hiresApplies)
        }
    }
    val crossfadeTitle = stringResource(R.string.pref_crossfade)
    val gaplessTitle = stringResource(R.string.pref_gapless)
    val themeTitle = stringResource(R.string.pref_theme)
    val themeValue = stringResource(themeModeLabel(themeMode))
    val themeCaption = stringResource(R.string.pref_theme_caption)
    // Resolved here because the segment labels are read inside a plain lambda, not a composable.
    val themeLabels = ThemeMode.entries.associateWith { stringResource(themeModeLabel(it)) }
    val layoutTitle = stringResource(R.string.pref_player_layout)
    val layoutValue = stringResource(playerLayoutLabel(playerLayout))
    val layoutCaption = stringResource(playerLayoutCaption(playerLayout))
    val lyricsTitle = stringResource(R.string.pref_lyrics_quality)
    val lyricsValue = stringResource(lyricsQualityLabel(lyricsQuality))
    val lyricsCaption = stringResource(lyricsQualityCaption(lyricsQuality))
    val canvasTitle = stringResource(R.string.pref_canvas)
    val canvasValue = stringResource(
        if (canvasEnabled && !savingActive) R.string.pref_canvas_on else R.string.pref_canvas_off,
    )
    val pausedBySaver = stringResource(R.string.pref_paused_by_data_saver)
    val canvasNetworkText = stringResource(canvasNetworkLabel(canvasNetwork))
    val canvasEnableCaption = stringResource(R.string.pref_canvas_enable_caption)
    // Switched on but not running: say which of the two it is, rather than showing "On" over a cover
    // that never animates.
    val canvasCaption = when {
        savingActive -> pausedBySaver
        canvasEnabled -> canvasNetworkText
        else -> canvasEnableCaption
    }
    val pluginsTitle = stringResource(R.string.pref_plugins)
    val pluginsValue = stringResource(R.string.pref_plugins_v)
    val feedTitle = stringResource(R.string.pref_feed_provider)
    val feedAll = stringResource(R.string.feed_provider_all)
    val feedValue = feedOptions.firstOrNull { it.id == feedProvider }?.name ?: feedAll
    val feedCaption = stringResource(R.string.pref_feed_provider_caption)
    val radioTitle = stringResource(R.string.pref_radio_algorithm)
    val radioValue = stringResource(radioAlgorithmLabel(radioAlgorithm))
    val radioCaption = stringResource(radioAlgorithmCaption(radioAlgorithm))
    val regionalTitle = stringResource(R.string.settings_regional_recs)
    val regionalOff = stringResource(R.string.settings_regional_recs_off)
    val regionalUnset = stringResource(R.string.settings_regional_recs_unset)
    val regionalOn = vm.regionCountry?.let { stringResource(R.string.settings_regional_recs_on, it) }
    val regionalCaption = when (regionalRecs) {
        true -> regionalOn ?: regionalOff
        false -> regionalOff
        null -> regionalUnset
    }
    val formatTitle = stringResource(R.string.pref_download_format)
    val formatValue = stringResource(downloadFormatLabel(downloadFormat))
    val formatCaption = stringResource(downloadFormatCaption(downloadFormat))
    val saveTitle = stringResource(R.string.pref_save_to_phone)
    val saveCaption = stringResource(R.string.pref_save_to_phone_caption)
    val dataSaverTitle = stringResource(R.string.pref_data_saver)
    val dataSaverSystem = stringResource(R.string.pref_data_saver_system)
    val dataSaverOwn = stringResource(R.string.pref_data_saver_caption)
    // When Android's own Data saver is what turned this on, say so — otherwise the row reads as off
    // while everything behaves as though it were on.
    val dataSaverCaption = if (savingActive && !dataSaver) dataSaverSystem else dataSaverOwn
    val cacheTitle = stringResource(R.string.pref_offline_cache)
    val cacheCaption = stringResource(R.string.pref_offline_cache_cap)
    val clearTitle = stringResource(R.string.pref_clear_cache)
    val langTitle = stringResource(R.string.pref_language)
    val langSystem = stringResource(R.string.language_system)
    val langValue = if (currentLang == AppLanguage.SYSTEM) langSystem else currentLang.endonym
    val langCaption = stringResource(R.string.pref_language_caption)
    // The equalizer row reads what is in charge of the curve: the automatic equalizer, or the presets.
    val eqValueShown = if (autoEq) stringResource(R.string.eq_auto_badge) else eqValue
    val aboutTitle = stringResource(R.string.pref_about)
    val aboutValue = stringResource(R.string.pref_about_v)
    val accountTitle = stringResource(R.string.pref_account_sync)
    val accountValue = stringResource(R.string.pref_account_optional)
    val accountCaption = stringResource(R.string.pref_account_caption)

    // ---- The screen, as eight themed groups ---------------------------------------------------
    // Grouped by what the setting *is about*, which is how someone looks for one. The old single
    // "Playback" section held eleven rows — a screenful before Appearance even began — and mixed a
    // whole sub-app (Plugins) with lone switches, while "Save to the phone" sat there and the cache
    // it writes to sat under Data.
    val groups: List<Pair<String, List<SettingsEntry>>> = listOf(
        stringResource(R.string.settings_sound) to buildList {
            add(entry(eqTitle, eqValue) { SettingRow(eqTitle, eqValueShown, onClick = onOpenEqualizer) })
            // Sits right under the equalizer row it takes over, and carries a caption because
            // "automatic equalizer" doesn't say *what* it automates — the genre, then the song.
            add(
                entry(autoEqTitle, autoEqCaption) {
                    ToggleRowDetail(autoEqTitle, autoEqCaption, autoEq) { vm.setAutoEq(!autoEq) }
                },
            )
            // The other thing that changes how a song sounds without the listener asking per-track.
            add(
                entry(spatialTitle, spatialCaption) {
                    ToggleRowDetail(spatialTitle, spatialCaption, spatialOn) { vm.setSpatialAudio(!spatialOn) }
                },
            )
            // Only while the effect is on: a control that does nothing is worse than a shorter screen.
            if (spatialOn) {
                add(
                    entry(avoidDoubleTitle, avoidDoubleCaption) {
                        ToggleRowDetail(avoidDoubleTitle, avoidDoubleCaption, avoidDoubleSpatial) {
                            vm.setAvoidDoubleSpatialization(!avoidDoubleSpatial)
                        }
                    },
                )
            }
            add(entry(normalizeTitle) { ToggleRow(normalizeTitle, normalize) { vm.setNormalize(!normalize) } })
            // One row rather than five: the mode, the network rule, the download rule and the readout
            // are facets of the same decision, and the last three are moot while the mode is Standard.
            add(
                entry(audioQualityTitle, audioQualityValue, audioQualityCaption) {
                    SettingRow(audioQualityTitle, audioQualityValue, audioQualityCaption) {
                        // A plugin can have been installed since this screen was last drawn, which is
                        // exactly what turns Lossless from unavailable into available.
                        vm.refreshLosslessAvailability()
                        audioQualityDialogOpen = true
                    }
                },
            )
        },
        stringResource(R.string.settings_playback) to listOf(
            entry(crossfadeTitle) { ToggleRow(crossfadeTitle, crossfade) { vm.setCrossfade(!crossfade) } },
            entry(gaplessTitle) { ToggleRow(gaplessTitle, gapless) { vm.setGapless(!gapless) } },
        ),
        stringResource(R.string.settings_appearance) to listOf(
            // Three segments in the row itself, as on the web: the choice is small enough to be seen whole.
            entry(themeTitle, themeValue, themeCaption) {
                ControlRow(themeTitle, themeCaption, stackOnPhone = true) {
                    SegmentedTabs(ThemeMode.entries, themeMode, label = { themeLabels.getValue(it) }, onSelect = onSetThemeMode)
                }
            },
            entry(layoutTitle, layoutValue, layoutCaption) {
                SettingRow(layoutTitle, layoutValue, layoutCaption) { playerLayoutDialogOpen = true }
            },
            // The karaoke sweep is the one screen that asks for a frame every frame. Automatic steps
            // itself down on a phone that can't afford it; this row is the manual override both ways.
            entry(lyricsTitle, lyricsValue, lyricsCaption) {
                SettingRow(lyricsTitle, lyricsValue, lyricsCaption) { lyricsQualityDialogOpen = true }
            },
            // One row rather than seven: sources, quality, network rule, battery rule and diagnostics
            // all belong to the same decision, and every one is meaningless while it is off.
            entry(canvasTitle, canvasValue, canvasCaption) {
                SettingRow(canvasTitle, canvasValue, canvasCaption) { canvasDialogOpen = true }
            },
        ),
        // Where the music itself comes from. The feed picker lives here rather than under
        // Recommendations because its options *are* the enabled sources on the row above it.
        stringResource(R.string.settings_sources) to listOf(
            entry(pluginsTitle, pluginsValue) { SettingRow(pluginsTitle, pluginsValue, onClick = onOpenSources) },
            entry(feedTitle, feedValue, feedCaption) {
                SettingRow(feedTitle, feedValue, feedCaption) { vm.refreshFeedSources(); feedDialogOpen = true }
            },
        ),
        stringResource(R.string.settings_recs_section) to listOf(
            // Both are real recommendation systems, they just read different things — YT Music reads
            // the *song*, Deezer reads its *artist* — so it is a taste choice, not a quality one.
            entry(radioTitle, radioValue, radioCaption) {
                SettingRow(radioTitle, radioValue, radioCaption) { radioDialogOpen = true }
            },
            entry(regionalTitle, regionalCaption) {
                ToggleRowDetail(regionalTitle, regionalCaption, regionalRecs == true) {
                    vm.setRegionalRecs(regionalRecs != true)
                }
            },
        ),
        stringResource(R.string.settings_downloads) to listOf(
            entry(formatTitle, formatValue, formatCaption) {
                SettingRow(formatTitle, formatValue, formatCaption) { downloadFormatDialogOpen = true }
            },
            // Right under the format, because both answer "what do I end up with?". A download is
            // otherwise app-private: it plays offline but no file manager and no other player sees it.
            entry(saveTitle, saveCaption) {
                ToggleRowDetail(saveTitle, saveCaption, saveToPhone == true) {
                    if (saveToPhone != true) ensureSavePermission { vm.setSaveToPhone(true) }
                    else vm.setSaveToPhone(false)
                }
            },
        ),
        stringResource(R.string.settings_data_storage) to listOf(
            entry(dataSaverTitle, dataSaverCaption) {
                ToggleRowDetail(dataSaverTitle, dataSaverCaption, dataSaver) { vm.setDataSaver(!dataSaver) }
            },
            // Tapping cycles the limit rather than opening a dialog: four values, and this row already
            // sits next to "Clear cache", which is where someone worried about space is looking. No
            // chevron — it does not navigate, and a chevron that leads nowhere is a small lie.
            entry(cacheTitle, cacheLimit, cacheCaption) {
                SettingRow(cacheTitle, cacheLimit, cacheCaption, chevron = false, onClick = vm::cycleAudioCacheLimit)
            },
            // Destructive and irreversible, so it asks first — and no chevron, for the same reason.
            entry(clearTitle, cacheSize) {
                SettingRow(clearTitle, cacheSize, chevron = false) { clearCacheOpen = true }
            },
        ),
        stringResource(R.string.settings_app) to listOf(
            entry(accountTitle, accountValue, accountCaption) {
                SettingRow(accountTitle, accountValue, accountCaption, onClick = onOpenAccount)
            },
            // Tapping opens a picker; the OS owns the per-app locale, so the choice persists and also
            // shows under Android's own per-app Language page.
            entry(langTitle, langValue, langCaption) {
                ControlRow(langTitle, langCaption) { EditorialSelect(langValue) { languageDialogOpen = true } }
            },
            entry(aboutTitle, aboutValue) { SettingRow(aboutTitle, aboutValue, onClick = onOpenAbout) },
        ),
    )

    ResponsiveContent(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pagePadding()),
    ) {
        // The design's page head: kicker, the display title, one sentence, and the search field — over
        // a 2dp rule. Twenty-three rows over roughly three screens: without the field, finding one
        // means remembering which of eight groups it lives in. Same matcher as the Library's filter.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .bottomRule(c.hardLine, Editorial.Frame)
                .padding(bottom = 25.dp),
        ) {
            Kicker(stringResource(R.string.settings_kicker))
            DisplayTitle(stringResource(R.string.settings_hero_title), Modifier.padding(top = 8.dp))
            Lede(stringResource(R.string.settings_lede), Modifier.padding(top = 16.dp))
            EditorialSearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.settings_search_hint),
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        // The account, first — it was a plain row at the bottom of the eighth group, which in
        // practice meant nobody found where to sign in. The design's ink card: face, state and one tap
        // into the flow. It answers the search too, so filtering for "account" keeps it on screen.
        val accountState by accountVm.accountState.collectAsStateWithLifecycle()
        val accountMatches = ListFilter.matches(query, accountTitle, accountCaption, accountValue)
        if (accountState != AccountState.Disabled && accountMatches) {
            val accountProfile = (accountState as? AccountState.SignedIn)?.profile
            Box(Modifier.fillMaxWidth().padding(top = 28.dp, end = 7.dp, bottom = 7.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .brutalShadow(c.redAccent.copy(alpha = 0.28f), offset = 7.dp)
                        .background(c.accent)
                        .border(Editorial.Frame, c.hardLine)
                        .clickableScale(scale = 0.99f, onClick = onOpenAccount)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(64.dp).border(1.dp, c.onFill.copy(alpha = 0.65f))) {
                        AccountAvatar(accountState, size = 64.dp, iconSize = 26.dp, bare = true)
                    }
                    Column(Modifier.weight(1f)) {
                        CodeLabel(stringResource(R.string.settings_account_eyebrow), color = c.redAccent, size = 9)
                        Text(
                            when (accountState) {
                                is AccountState.SignedIn -> accountProfile?.displayName ?: accountProfile?.email
                                    ?: stringResource(R.string.account_connected)
                                is AccountState.Guest -> stringResource(R.string.account_guest)
                                else -> stringResource(R.string.account_sign_in)
                            },
                            style = sg(19, FontWeight.Medium, -0.02f),
                            color = c.onFill,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            when (accountState) {
                                is AccountState.SignedIn -> stringResource(R.string.settings_account_card_caption)
                                is AccountState.Guest -> stringResource(R.string.account_guest_caption)
                                else -> stringResource(R.string.pref_account_caption)
                            },
                            style = mr(11, FontWeight.Medium),
                            color = c.onFill.copy(alpha = 0.63f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    RowArrow(color = c.onFill)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        groups.forEachIndexed { index, (title, rows) ->
            SettingsGroup(SECTION_META.getOrElse(index) { SECTION_META.last() }, title, query, rows)
        }
        if (!accountMatches && groups.none { (_, rows) -> rows.any { it.matches(query) } }) {
            EmptyBlock(
                title = stringResource(R.string.settings_no_matches),
                body = stringResource(R.string.settings_empty_hint),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // Ends above the floating chrome instead of behind it (measured, see LocalBottomInset).
        Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
    }

    // Emptying the cache cannot be undone, and the row used to do it on the first tap with no warning.
    if (clearCacheOpen) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_cache_title),
            body = stringResource(R.string.settings_clear_cache_body),
            confirmLabel = stringResource(R.string.settings_clear_cache_confirm),
            onConfirm = vm::clearCache,
            onDismiss = { clearCacheOpen = false },
        )
    }
    if (languageDialogOpen) {
        LanguageDialog(
            current = currentLang,
            onSelect = { lang -> setAppLanguage(context, lang); languageDialogOpen = false },
            onDismiss = { languageDialogOpen = false },
        )
    }
    if (playerLayoutDialogOpen) {
        PlayerLayoutDialog(
            current = playerLayout,
            onSelect = { layout -> vm.setPlayerLayout(layout); playerLayoutDialogOpen = false },
            onDismiss = { playerLayoutDialogOpen = false },
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
            // Not `DownloadFormat.entries`: Opus doesn't exist as an option below API 29 (no Ogg muxer).
            options = availableDownloadFormats(),
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
            tidalEnabled = canvasTidal,
            youtubeEnabled = canvasYoutube,
            diagnostics = canvasDiagnostics,
            onSetEnabled = vm::setCanvasEnabled,
            onSetNetwork = vm::setCanvasNetwork,
            onSetQuality = vm::setCanvasQuality,
            onSetBatterySaver = vm::setCanvasOnBatterySaver,
            onSetApple = vm::setCanvasApple,
            onSetTidal = vm::setCanvasTidal,
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
                // reason rather than hidden, because unlike a plugin kept out of the store this is one
                // step away from working — and an option that silently isn't there can't be looked for.
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
    tidalEnabled: Boolean,
    youtubeEnabled: Boolean,
    diagnostics: CanvasDiagnostics,
    onSetEnabled: (Boolean) -> Unit,
    onSetNetwork: (CanvasNetworkPolicy) -> Unit,
    onSetQuality: (CanvasQuality) -> Unit,
    onSetBatterySaver: (Boolean) -> Unit,
    onSetApple: (Boolean) -> Unit,
    onSetTidal: (Boolean) -> Unit,
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
                // One switch per source, because they fail differently. Apple and TIDAL either have
                // this album's loop or they haven't; YouTube is a search, and a search is the thing
                // that can be wrong.
                DialogToggleRow(
                    title = stringResource(R.string.pref_canvas_apple),
                    caption = stringResource(R.string.pref_canvas_apple_caption),
                    checked = appleEnabled,
                    onToggle = { onSetApple(!appleEnabled) },
                )
                DialogToggleRow(
                    title = stringResource(R.string.pref_canvas_tidal),
                    caption = stringResource(R.string.pref_canvas_tidal_caption),
                    checked = tidalEnabled,
                    onToggle = { onSetTidal(!tidalEnabled) },
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

/**
 * A small heading inside a dialog. Deliberately *not* the list's [SectionHeader]: a red marker and a
 * 19sp title inside a picker would outrank the picker's own title.
 */
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

/** The @StringRes label for a [PlayerLayout], shown in the row and the picker. */
private fun playerLayoutLabel(layout: PlayerLayout): Int = when (layout) {
    PlayerLayout.CLASSIC -> R.string.player_layout_classic
    PlayerLayout.COMPACT -> R.string.player_layout_compact
}

private fun playerLayoutCaption(layout: PlayerLayout): Int = when (layout) {
    PlayerLayout.CLASSIC -> R.string.player_layout_classic_caption
    PlayerLayout.COMPACT -> R.string.player_layout_compact_caption
}

@Composable
private fun PlayerLayoutDialog(
    current: PlayerLayout,
    onSelect: (PlayerLayout) -> Unit,
    onDismiss: () -> Unit,
) = CaptionedOptionDialog(
    title = stringResource(R.string.pref_player_layout),
    options = PlayerLayout.entries,
    current = current,
    label = { stringResource(playerLayoutLabel(it)) },
    caption = { stringResource(playerLayoutCaption(it)) },
    onSelect = onSelect,
    onDismiss = onDismiss,
)

/** The @StringRes label for a [ThemeMode], shown in the row and the picker. */
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
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

/**
 * One settings row plus the words it can be found by.
 *
 * [keywords] is what the search matches against — the row's title, its value and its caption — so a
 * setting is reachable by any of the three, not only by the name someone happens to remember.
 */
internal class SettingsEntry(private val keywords: List<String?>, val content: @Composable () -> Unit) {
    /** Stable across filtering, so a row keeps its identity as its neighbours come and go. */
    val key: String = keywords.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    fun matches(query: String): Boolean = ListFilter.matches(query, *keywords.toTypedArray())
}

internal fun entry(vararg keywords: String?, content: @Composable () -> Unit) =
    SettingsEntry(keywords.toList(), content)

/** The rows of [rows] a [query] leaves standing. A group with none of them draws nothing at all. */
internal fun visibleEntries(query: String, rows: List<SettingsEntry>): List<SettingsEntry> =
    rows.filter { it.matches(query) }

/**
 * The eyebrow and serial of a section (`■ AUDIO … S01`), by position in the groups list. The titles
 * stay where they are built; only the design's two decorations live here.
 */
private data class SectionMeta(@StringRes val eyebrow: Int, val code: String)

private val SECTION_META = listOf(
    SectionMeta(R.string.settings_eyebrow_sound, "S01"),
    SectionMeta(R.string.settings_eyebrow_playback, "P02"),
    SectionMeta(R.string.settings_eyebrow_appearance, "V03"),
    SectionMeta(R.string.settings_eyebrow_sources, "C04"),
    SectionMeta(R.string.settings_eyebrow_recs, "R05"),
    SectionMeta(R.string.settings_eyebrow_downloads, "D06"),
    SectionMeta(R.string.settings_eyebrow_data, "M07"),
    SectionMeta(R.string.settings_eyebrow_app, "A08"),
)

/** True on a section's last visible row, which draws no rule under it — the card's frame is the closing line. */
private val LocalLastRow = compositionLocalOf { false }

/**
 * A themed group of settings as the design's framed section card: eyebrow, title and serial over a
 * 2dp rule, then whichever of its rows survive the filter. Draws nothing at all when none of them do —
 * an empty card is worse than a missing one.
 */
@Composable
private fun SettingsGroup(meta: SectionMeta, title: String, query: String, rows: List<SettingsEntry>) {
    val visible = visibleEntries(query, rows)
    if (visible.isEmpty()) return
    val c = RizxTheme.colors
    EditorialSurface(Modifier.padding(bottom = 14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .bottomRule(c.hardLine, Editorial.Frame)
                .padding(bottom = 18.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1f)) {
                SignalEyebrow(stringResource(meta.eyebrow))
                SurfaceTitle(title, Modifier.padding(top = 6.dp))
            }
            Text(meta.code, style = mr(11, FontWeight.Medium), color = c.muted)
        }
        visible.forEachIndexed { index, row ->
            key(row.key) {
                CompositionLocalProvider(LocalLastRow provides (index == visible.lastIndex)) { row.content() }
            }
        }
    }
}

/**
 * A tappable settings row: title (with optional explanatory [caption]) on the left, short [value] on the
 * right, the design's `→` last.
 *
 * **The value has to be width-bounded.** Compose measures unweighted children first against the *whole*
 * row, so an unbounded value took everything and left the title and the arrow zero width — the
 * "Offline cache" row rendered as a floating sentence with no title and no arrow at all. Capping it at
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
    /**
     * False for rows that do not navigate. "Offline cache" cycles its own value in place and "Clear
     * cache" performs an action — an arrow on either promises a screen that never arrives.
     */
    chevron: Boolean = true,
    onClick: () -> Unit = {},
) {
    val c = RizxTheme.colors
    val valueMax = (LocalConfiguration.current.screenWidthDp * 0.34f).dp
    Row(
        Modifier.fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .settingRowFrame(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowText(title, caption, Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = mr(11, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = valueMax),
            )
        }
        if (chevron) RowArrow(size = 19)
    }
}

/**
 * A row whose right side is a control of its own — the theme segments, the language select.
 *
 * [stackOnPhone] puts a wide control on its own line under the text at phone widths: three segments
 * beside "Theme" left the caption a column of single words, which the design's wrapping flex row
 * avoids by dropping the segments down.
 */
@Composable
private fun ControlRow(
    title: String,
    caption: String?,
    stackOnPhone: Boolean = false,
    control: @Composable () -> Unit,
) {
    if (stackOnPhone && !isTablet()) {
        Column(Modifier.fillMaxWidth().settingRowFrame()) {
            RowText(title, caption)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.CenterEnd) { control() }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().settingRowFrame(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowText(title, caption, Modifier.weight(1f))
        control()
    }
}

/** The title and caption of a row, in the design's sizes: mono 14 over 11 muted, the caption capped at ~38 characters. */
@Composable
private fun RowText(title: String, caption: String?, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Column(modifier) {
        Text(title, style = mr(14, FontWeight.Medium), color = c.text)
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                style = mr(11, FontWeight.Medium, lineHeight = 15),
                color = c.muted,
                modifier = Modifier.padding(top = 4.dp).widthIn(max = 280.dp),
            )
        }
    }
}

/**
 * What every row shares: the minimum height, the padding, and the rule under it — except on a
 * section's last row, where the card's own frame closes the list, as on the web.
 */
@Composable
private fun Modifier.settingRowFrame(): Modifier {
    val last = LocalLastRow.current
    return this
        .then(if (last) Modifier else Modifier.bottomRule(Editorial.rule))
        .heightIn(min = ROW_MIN_HEIGHT)
        .padding(vertical = ROW_PADDING, horizontal = 2.dp)
}

/**
 * The whole row is the switch — that is what a screen reader is told, via [Role.Switch] plus the
 * merged title as its name and [toggleableState] as its state. Before this, the row was an unnamed
 * clickable and the knob a second, equally unnamed one: TalkBack read the settings list as a column
 * of anonymous buttons. The knob is [RizxToggle]'s `decorative` for the same reason.
 */
@Composable
internal fun ToggleRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    val c = RizxTheme.colors
    val spokenName = title
    Row(
        Modifier.fillMaxWidth()
            // **Before** the click, not after. Modifiers wrap outside-in, so a merging semantics node
            // placed after `clickableScale` ends up *inside* the clickable one — the accessibility tree
            // then reports a nameless checkable node with the title as a separate sibling, which is
            // precisely the bug this was meant to fix. Verified against a uiautomator dump.
            .switchSemantics(checked, spokenName, onToggle)
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onToggle)
            .settingRowFrame(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = mr(14, FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
        RizxToggle(checked = checked, onToggle = onToggle, decorative = true)
    }
}

/**
 * Announces the row as a single switch: [name] is what it is called, and the state is on/off.
 *
 * `clearAndSetSemantics` rather than `semantics(mergeDescendants = true)`, and that is not a style
 * preference — the merging form was tried and verified against a uiautomator dump twice. It reported a
 * checkable node with the right state but **no name at all**, with the title still sitting beside it as
 * a separate node; a screen reader would have said "on, switch" and left the user to guess of what.
 * Clearing is deterministic: exactly one node, named, stated and actionable.
 */
private fun Modifier.switchSemantics(checked: Boolean, name: String, onToggle: () -> Unit): Modifier =
    clearAndSetSemantics {
        role = Role.Switch
        toggleableState = ToggleableState(checked)
        contentDescription = name
        // The clear takes the row's own click with it, so the action is re-declared here — otherwise
        // the switch would be announced correctly and then refuse to be activated.
        onClick { onToggle(); true }
    }

/** The design's row padding (`14px 2px`). */
private val ROW_PADDING = 14.dp

/** The design's phone row height; also comfortably above Android's 48dp touch minimum. */
private val ROW_MIN_HEIGHT = 78.dp

/** A [ToggleRow] with a muted caption line under the title — used to carry the Hi-Res explainer + the
 *  device's DAC capability, without a second (misleading) chevron row. */
@Composable
internal fun ToggleRowDetail(title: String, caption: String, checked: Boolean, onToggle: () -> Unit) {
    val c = RizxTheme.colors
    // The caption is spoken too. Clearing the node would otherwise drop the very sentence that says
    // what the switch does — the sighted reader gets it, and the screen-reader user would not.
    val spokenName = if (caption.isEmpty()) title else "$title. $caption"
    Row(
        Modifier.fillMaxWidth()
            // **Before** the click, not after. Modifiers wrap outside-in, so a merging semantics node
            // placed after `clickableScale` ends up *inside* the clickable one — the accessibility tree
            // then reports a nameless checkable node with the title as a separate sibling, which is
            // precisely the bug this was meant to fix. Verified against a uiautomator dump.
            .switchSemantics(checked, spokenName, onToggle)
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onToggle)
            .settingRowFrame(),
        // Top, not centre: the switch belongs to the *title*. Centred against a multi-line caption it
        // floated in the middle of a paragraph, reading as if it belonged to the explanation.
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowText(title, caption, Modifier.weight(1f))
        RizxToggle(checked = checked, onToggle = onToggle, decorative = true)
    }
}
