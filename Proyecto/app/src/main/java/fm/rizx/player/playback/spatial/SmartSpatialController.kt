package fm.rizx.player.playback.spatial

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.data.genre.TrackGenreResolver
import fm.rizx.player.data.local.store.SpatialAudioProfileStore
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.SpatialAnalysis
import fm.rizx.player.domain.model.SpatialAudioMode
import fm.rizx.player.domain.model.SpatialAudioState
import fm.rizx.player.domain.model.SpatialInactiveReason
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.SpatialAudioController
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.SmartSpatialProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides what the spatializer should be doing, song by song.
 *
 * Shaped after `AutoEqualizer`, which solves the same problem for the equaliser and solves it well: an
 * `attach`/`release` pair driven by the playback service, and nested `collectLatest` so that changing
 * song **cancels the previous song's work outright**. Holding "next" down therefore does not queue up a
 * genre lookup and a sixteen-second measurement per track — only the song you land on is shaped.
 *
 * Order of business per song, and the first step is the one that matters:
 *
 * 1. a profile is applied **immediately**, from cache or from the genre, so the effect is there from the
 *    first bar. Nothing ever waits for analysis;
 * 2. the recording is measured while it plays;
 * 3. the refined profile replaces the first one by gliding, over a second or two, so the change is
 *    something you notice afterwards rather than something you hear happen.
 *
 * Failures here are never allowed to reach playback: a provider that cannot say what genre a song is,
 * or a cache that will not open, costs the song its refinement and nothing else.
 */
@UnstableApi
@Singleton
class SmartSpatialController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val queue: QueueRepository,
    private val genres: TrackGenreResolver,
    private val analyzer: SpatialTrackAnalyzer,
    private val store: SpatialAudioProfileStore,
    private val route: SpatialOutputRoute,
    private val engine: StereoPcmTransform,
    private val sinkState: SpatialSinkState,
) : SpatialAudioController {

    private val _state = MutableStateFlow(SpatialAudioState())
    override val state: StateFlow<SpatialAudioState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null

    /**
     * Separate from [scope] and never cancelled. The toggle has to work from the settings screen with
     * no music playing, and at that moment there is no playback service and therefore no session scope
     * to write the preference on.
     */
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called from `PlaybackService.onCreate`, and again after every `release`. */
    fun attach() {
        release()
        val created = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = created
        created.launch {
            combine(
                settings.spatialAudioMode,
                settings.avoidDoubleSpatialization,
                route.headphonesConnected,
            ) { mode, avoidDouble, headphones -> Conditions(mode, avoidDouble, headphones) }
                .distinctUntilChanged()
                .collectLatest { run(it) }
        }
    }

    fun release() {
        scope?.cancel()
        scope = null
        analyzer.stop()
        engine.setEnabled(false)
    }

    override fun setEnabled(enabled: Boolean) {
        val target = if (enabled) SpatialAudioMode.SMART_8D else SpatialAudioMode.OFF
        commands.launch { settings.setSpatialAudioMode(target) }
    }

    private data class Conditions(
        val mode: SpatialAudioMode,
        val avoidDouble: Boolean,
        val headphones: Boolean,
    )

    private suspend fun run(conditions: Conditions) {
        val blocked = when {
            conditions.mode == SpatialAudioMode.OFF -> SpatialInactiveReason.MODE_OFF
            !conditions.headphones -> SpatialInactiveReason.SPEAKER_OUTPUT
            conditions.avoidDouble && systemIsSpatializing() -> SpatialInactiveReason.SYSTEM_SPATIALIZER_ACTIVE
            else -> SpatialInactiveReason.NONE
        }

        if (blocked != SpatialInactiveReason.NONE) {
            engine.setEnabled(false)
            analyzer.stop()
            publish(conditions, active = false, reason = blocked, label = null, measured = false)
            return
        }

        engine.setEnabled(true)
        try {
            queue.state
                .map { it.current?.track }
                .distinctUntilChanged { old, new -> old?.source?.identityKey == new?.source?.identityKey }
                .collectLatest { track ->
                    if (track == null) {
                        publish(conditions, active = true, label = null, measured = false)
                    } else {
                        shape(track, conditions)
                    }
                }
        } finally {
            // Switching off, or the service going away: stop measuring and stop processing.
            analyzer.stop()
            engine.setEnabled(false)
        }
    }

    private suspend fun shape(track: Track, conditions: Conditions) {
        val key = track.source.identityKey
        val cached = runCatching { store.get(key) }.getOrNull()

        // A song heard before needs neither the genre lookup nor the measurement — but it is still run
        // through the factory, so any retuning of the presets reaches songs already heard.
        val genre = cached?.genre?.let { name -> SoundGenre.entries.firstOrNull { it.name == name } }
            ?: runCatching { genres.resolve(track).genre }.getOrNull()
            ?: SoundGenre.UNKNOWN

        apply(genre, cached?.analysis, conditions)

        if (cached?.analysis != null) {
            analyzer.stop()
            return
        }

        analyzer.reset()
        val measured = withTimeoutOrNull(MEASURE_TIMEOUT_MS) { analyzer.analysis.first { it != null } }
        analyzer.stop()
        if (measured == null) return

        apply(genre, measured, conditions)
        runCatching { store.put(key, genre.name, measured) }
    }

    private fun apply(genre: SoundGenre, analysis: SpatialAnalysis?, conditions: Conditions) {
        val profile = SmartSpatialProfiles.profileFor(genre, analysis)
        engine.setProfile(profile)
        publish(conditions, active = true, label = profile.label, measured = analysis != null)
    }

    private fun publish(
        conditions: Conditions,
        active: Boolean,
        reason: SpatialInactiveReason = SpatialInactiveReason.NONE,
        label: String?,
        measured: Boolean,
    ) {
        // A format the sink cannot handle is reported even while everything else says "on", because from
        // the listener's side it is the same situation: enabled, and not currently doing anything.
        val effective = if (active && !sinkState.supported) sinkState.reason else reason
        _state.value = SpatialAudioState(
            mode = conditions.mode,
            active = active && effective == SpatialInactiveReason.NONE,
            inactiveReason = effective,
            profileLabel = label,
            analysisReady = measured,
        )
    }

    /**
     * Whether Android is already applying spatial audio of its own.
     *
     * Two of them in series do not add up; they smear the image, because the second one is placing a
     * sound that has already been placed. API 32 is where the platform first says.
     */
    private fun systemIsSpatializing(): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        return runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val spatializer = audio.spatializer
            spatializer.isEnabled && spatializer.isAvailable
        }.getOrDefault(false)
    }

    private companion object {
        /** Give up on measuring rather than leave a song unshaped for ever. */
        const val MEASURE_TIMEOUT_MS = 45_000L
    }
}
