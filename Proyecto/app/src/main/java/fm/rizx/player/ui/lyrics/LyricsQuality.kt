package fm.rizx.player.ui.lyrics

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import fm.rizx.player.domain.model.LyricsVisualQuality

/** What the karaoke renderer is allowed to do this session, once the device has had its say. */
@Immutable
data class LyricsRenderProfile(
    /** Frames per second the sweep asks for. */
    val hz: Int,
    /** Sweep continuously across letters, or step in whole words like the pre-karaoke renderer. */
    val sweep: Boolean,
    /** Use the full-width red bloom at the moving edge; constrained devices keep a narrower edge. */
    val bloom: Boolean,
    /** Animate the active-line emphasis (the short horizontal shift from the web design). */
    val activeLineMotion: Boolean,
) {
    companion object {
        /** Used before the preference has loaded, and by previews. */
        val Default = LyricsRenderProfile(hz = 60, sweep = true, bloom = true, activeLineMotion = true)
    }
}

/**
 * Resolves [quality] against the device.
 *
 * `AUTOMATIC` is the default and does the stepping down on the user's behalf: a phone in power-save mode
 * gets half the frames and no halo, and a low-RAM device never gets the halo at all — a blurred layer per
 * frame is the part of this screen that will fall over first.
 */
@Composable
fun rememberLyricsRenderProfile(quality: LyricsVisualQuality): LyricsRenderProfile {
    val context = LocalContext.current
    return remember(quality, context) { profileFor(quality, context) }
}

private fun profileFor(quality: LyricsVisualQuality, context: Context): LyricsRenderProfile =
    when (quality) {
        LyricsVisualQuality.HIGH -> LyricsRenderProfile(hz = 60, sweep = true, bloom = true, activeLineMotion = true)

        LyricsVisualQuality.BATTERY_SAVER ->
            LyricsRenderProfile(hz = 8, sweep = false, bloom = false, activeLineMotion = false)

        LyricsVisualQuality.AUTOMATIC -> {
            val saving = context.getSystemService<PowerManager>()?.isPowerSaveMode == true
            val lowRam = context.getSystemService<ActivityManager>()?.isLowRamDevice == true
            LyricsRenderProfile(
                hz = if (saving) 30 else 60,
                sweep = true,
                bloom = !saving && !lowRam,
                activeLineMotion = true,
            )
        }
    }
