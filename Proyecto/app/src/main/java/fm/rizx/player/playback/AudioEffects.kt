package fm.rizx.player.playback

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import fm.rizx.player.domain.model.EqBand
import fm.rizx.player.domain.model.EqBandRange
import fm.rizx.player.domain.model.EqPreset
import fm.rizx.player.domain.model.EqPresets
import fm.rizx.player.domain.model.EqualizerState
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Owns the platform [Equalizer] bound to the playback audio session (Phase 15). The `PlaybackService`
 * [attach]es it to the ExoPlayer session in `onCreate` and [release]s it in `onDestroy`; the UI drives
 * it through [AudioEffectsController]. Persisted enabled/band levels are re-applied on attach so the
 * equalizer survives restarts. Every native call is guarded — a device without effect support simply
 * reports `available = false` and never crashes playback.
 *
 * It is also the **single actuator** for the automatic equalizer ([AutoEqualizer]): one effect for both,
 * so there is no double processing and nothing to reconcile. While auto has the effect ([beginAuto]), the
 * levels written through [applyAutoCurve] are **never persisted** — the user's manual curve stays exactly
 * as they left it in DataStore, ready to be handed back by [endAuto].
 */
@Singleton
class AudioEffects @Inject constructor(
    private val settings: SettingsRepository,
) : AudioEffectsController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null

    /** True while [AutoEqualizer] owns the bands. */
    private var autoActive = false
    private var autoLabel: String? = null

    /**
     * The user's own curve and switch, as they currently stand — seeded when the effect is attached and
     * updated by every manual edit, so [endAuto] can give back exactly what was there.
     *
     * Deliberately *not* read off the effect inside [beginAuto]: `attach` restores the persisted levels from
     * DataStore asynchronously, so a `beginAuto` that raced it would capture a freshly-constructed (flat)
     * equalizer and hand that back as if it were the user's curve.
     */
    private var manualLevels: List<Int> = emptyList()
    private var manualEnabled = false

    /** The in-flight level ramp, so a track change supersedes the previous song's fade instead of racing it. */
    private var rampJob: Job? = null

    private val _state = MutableStateFlow(EqualizerState.Unavailable)
    override val state: StateFlow<EqualizerState> = _state.asStateFlow()

    /** Attach to [audioSessionId] (the ExoPlayer session) and re-apply persisted settings. */
    fun attach(audioSessionId: Int) {
        release()
        // Volume normalizer (Phase: Settings) — a fixed loudness boost, mounted on the same session as
        // the equalizer. Independent of the equalizer: a device without one may still support the other.
        loudness = runCatching { LoudnessEnhancer(audioSessionId).apply { setTargetGain(NORMALIZE_GAIN_MB) } }.getOrNull()
        scope.launch {
            val normalize = settings.normalizeVolume.first()
            loudness?.let { le -> runCatching { le.enabled = normalize } }
        }
        val eq = runCatching { Equalizer(EFFECT_PRIORITY, audioSessionId) }.getOrNull() ?: return
        equalizer = eq
        scope.launch {
            val enabled = settings.equalizerEnabled.first()
            val saved = settings.equalizerBandLevels.first()
            runCatching {
                eq.enabled = enabled
                if (saved.size == eq.numberOfBands.toInt()) {
                    saved.forEachIndexed { i, mb -> eq.setBandLevel(i.toShort(), mb.toShort()) }
                }
                // The user's curve, on the record from here on — even if the automatic equalizer takes the
                // effect over a moment later.
                manualEnabled = enabled
                manualLevels = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
            }
            publish()
        }
    }

    fun release() {
        rampJob?.cancel()
        rampJob = null
        autoActive = false
        autoLabel = null
        equalizer?.let { runCatching { it.release() } }
        equalizer = null
        loudness?.let { runCatching { it.release() } }
        loudness = null
        _state.value = EqualizerState.Unavailable
    }

    override fun setNormalizeVolume(enabled: Boolean) {
        loudness?.let { le -> runCatching { le.enabled = enabled } }
        scope.launch { settings.setNormalizeVolume(enabled) }
    }

    override fun setEqualizerEnabled(enabled: Boolean) {
        if (autoActive) return // auto holds the effect open; the screen hides this switch while it does
        val eq = equalizer ?: return
        runCatching { eq.enabled = enabled }
        manualEnabled = enabled
        scope.launch { settings.setEqualizerEnabled(enabled) }
        publish()
    }

    override fun setBandLevel(index: Int, millibel: Int) {
        // Auto owns the bands while it is on; a stray write here would be overwritten on the next track
        // anyway, and would take the user's saved curve with it.
        if (autoActive) return
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            eq.setBandLevel(index.toShort(), millibel.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
        }
        persistCurrent(eq)
        publish()
    }

    override fun applyPreset(preset: EqPreset) {
        if (autoActive) return
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            EqPresets.levels(preset, eq.numberOfBands.toInt(), range[1].toInt())
                .forEachIndexed { i, mb -> eq.setBandLevel(i.toShort(), mb.coerceIn(range[0].toInt(), range[1].toInt()).toShort()) }
        }
        persistCurrent(eq)
        publish()
    }

    /** Persists the preference only; [AutoEqualizer] watches it and performs the handover. */
    override fun setAutoEqualizer(enabled: Boolean) {
        scope.launch { settings.setAutoEqualizer(enabled) }
    }

    // ---- The automatic equalizer's side of the effect -----------------------------------------------

    /**
     * Hands the bands to the automatic equalizer.
     *
     * Forces the effect on: an auto curve written into a disabled equalizer is silence, which reads as "the
     * feature does nothing". Whether the user actually had it on is already recorded in [manualEnabled], and
     * [endAuto] puts it back.
     */
    fun beginAuto() {
        val eq = equalizer ?: return
        if (autoActive) return
        runCatching { eq.enabled = true }
        autoActive = true
        publish()
    }

    /** Gives the user's own curve back, exactly as they left it. */
    fun endAuto() {
        val eq = equalizer
        rampJob?.cancel()
        rampJob = null
        if (!autoActive) return
        autoActive = false
        autoLabel = null
        if (eq != null) {
            runCatching {
                val range = eq.bandLevelRange
                // Nothing recorded means nothing was ever set: a fresh install's own curve is flat, and
                // leaving the last song's auto curve behind would quietly adopt it as the user's.
                val restore = manualLevels.ifEmpty { List(eq.numberOfBands.toInt()) { 0 } }
                restore.forEachIndexed { i, mb ->
                    eq.setBandLevel(i.toShort(), mb.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                eq.enabled = manualEnabled
            }
        }
        publish()
    }

    /**
     * Moves the bands to [levelsMillibel] over [RAMP_MS], and labels the state with [label].
     *
     * Ramped because the platform equalizer applies a level change instantly: stepping a band by 6 dB in
     * one write is audible as a click. Nothing is persisted — see the class note.
     */
    fun applyAutoCurve(levelsMillibel: List<Int>, label: String?) {
        val eq = equalizer ?: return
        if (!autoActive) return
        autoLabel = label
        rampJob?.cancel()
        rampJob = scope.launch {
            val from = runCatching { (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() } }
                .getOrNull() ?: return@launch
            if (from.size != levelsMillibel.size) return@launch
            for (step in 1..RAMP_STEPS) {
                val t = step.toFloat() / RAMP_STEPS
                runCatching {
                    val range = eq.bandLevelRange
                    levelsMillibel.forEachIndexed { i, target ->
                        val value = (from[i] + (target - from[i]) * t).roundToInt()
                        eq.setBandLevel(i.toShort(), value.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                    }
                }
                publish()
                if (step < RAMP_STEPS) delay(RAMP_MS / RAMP_STEPS)
            }
        }
    }

    /** The device's bands as the curve engine needs them (empty when no effect is attached). */
    fun bandRanges(): List<EqBandRange> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            (0 until eq.numberOfBands).map { b ->
                val band = b.toShort()
                // Both APIs report milli-hertz; the range can come back as 0 / Int.MAX on the edges, which
                // the curve engine clamps.
                val edges = runCatching { eq.getBandFreqRange(band) }.getOrNull()
                EqBandRange(
                    index = b,
                    centerHz = eq.getCenterFreq(band) / 1000,
                    lowHz = edges?.getOrNull(0)?.let { it / 1000 } ?: 0,
                    highHz = edges?.getOrNull(1)?.let { it / 1000 } ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The device's allowed level span in millibels, or null when no effect is attached. */
    fun levelRangeMillibel(): IntRange? {
        val eq = equalizer ?: return null
        return runCatching { eq.bandLevelRange.let { it[0].toInt()..it[1].toInt() } }.getOrNull()
    }

    private fun persistCurrent(eq: Equalizer) {
        val levels = runCatching { (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() } }.getOrNull() ?: return
        manualLevels = levels // only reached from the manual mutators, so this *is* the user's curve
        scope.launch { settings.setEqualizerBandLevels(levels) }
    }

    private fun publish() {
        val eq = equalizer
        _state.value = if (eq == null) {
            EqualizerState.Unavailable
        } else {
            runCatching {
                val range = eq.bandLevelRange
                EqualizerState(
                    available = true,
                    enabled = eq.enabled,
                    minLevelMillibel = range[0].toInt(),
                    maxLevelMillibel = range[1].toInt(),
                    bands = (0 until eq.numberOfBands).map { b ->
                        EqBand(b, eq.getCenterFreq(b.toShort()) / 1000, eq.getBandLevel(b.toShort()).toInt())
                    },
                    auto = autoActive,
                    autoLabel = autoLabel,
                )
            }.getOrDefault(EqualizerState.Unavailable)
        }
    }

    private companion object {
        const val EFFECT_PRIORITY = 0

        /** Fixed loudness boost applied when "Normalize volume" is on (millibels; 600 mB = +6 dB). */
        const val NORMALIZE_GAIN_MB = 600

        /** Long enough that a whole new curve arrives as a swell rather than a step. */
        const val RAMP_MS = 400L
        const val RAMP_STEPS = 8
    }
}
