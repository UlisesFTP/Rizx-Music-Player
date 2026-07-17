package fm.rizx.player.playback

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import fm.rizx.player.domain.model.EqBand
import fm.rizx.player.domain.model.EqPreset
import fm.rizx.player.domain.model.EqPresets
import fm.rizx.player.domain.model.EqualizerState
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the platform [Equalizer] bound to the playback audio session (Phase 15). The `PlaybackService`
 * [attach]es it to the ExoPlayer session in `onCreate` and [release]s it in `onDestroy`; the UI drives
 * it through [AudioEffectsController]. Persisted enabled/band levels are re-applied on attach so the
 * equalizer survives restarts. Every native call is guarded — a device without effect support simply
 * reports `available = false` and never crashes playback.
 */
@Singleton
class AudioEffects @Inject constructor(
    private val settings: SettingsRepository,
) : AudioEffectsController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null

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
            }
            publish()
        }
    }

    fun release() {
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
        val eq = equalizer ?: return
        runCatching { eq.enabled = enabled }
        scope.launch { settings.setEqualizerEnabled(enabled) }
        publish()
    }

    override fun setBandLevel(index: Int, millibel: Int) {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            eq.setBandLevel(index.toShort(), millibel.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
        }
        persistCurrent(eq)
        publish()
    }

    override fun applyPreset(preset: EqPreset) {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            EqPresets.levels(preset, eq.numberOfBands.toInt(), range[1].toInt())
                .forEachIndexed { i, mb -> eq.setBandLevel(i.toShort(), mb.coerceIn(range[0].toInt(), range[1].toInt()).toShort()) }
        }
        persistCurrent(eq)
        publish()
    }

    private fun persistCurrent(eq: Equalizer) {
        val levels = runCatching { (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() } }.getOrNull() ?: return
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
                )
            }.getOrDefault(EqualizerState.Unavailable)
        }
    }

    private companion object {
        const val EFFECT_PRIORITY = 0

        /** Fixed loudness boost applied when "Normalize volume" is on (millibels; 600 mB = +6 dB). */
        const val NORMALIZE_GAIN_MB = 600
    }
}
