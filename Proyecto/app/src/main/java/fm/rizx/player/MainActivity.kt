package fm.rizx.player

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.ui.RizxApp
import fm.rizx.player.ui.player.PlayerViewModel
import fm.rizx.player.ui.screens.RizxSplash
import fm.rizx.player.ui.theme.LARGE_SCREEN_SW_DP
import fm.rizx.player.ui.theme.RizxTheme
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyOrientationPolicy()
        setContent {
            // The activity-scoped PlayerViewModel drives both the live theme and playback.
            val playerViewModel: PlayerViewModel = hiltViewModel()
            val themeMode by playerViewModel.themeMode.collectAsStateWithLifecycle()
            // SYSTEM (the default) follows the device; isSystemInDarkTheme() re-reads on a device dark-mode
            // change, so the app flips automatically when the phone does.
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            AskForNotificationsOnce()

            RizxTheme(darkTheme = isDark) {
                // The brand splash sits over the app while it wakes up, then dissolves into it. Saved
                // across configuration changes so a rotation doesn't replay it.
                var splashVisible by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(SPLASH_HOLD_MS)
                    splashVisible = false
                }

                Box(Modifier.fillMaxSize()) {
                    RizxApp(playerViewModel = playerViewModel)
                    AnimatedVisibility(
                        visible = splashVisible,
                        // No enter transition: the system splash already painted this exact field, so the
                        // handoff must be instant — only the exit is animated.
                        enter = EnterTransition.None,
                        exit = fadeOut(tween(durationMillis = 420)),
                    ) {
                        RizxSplash()
                    }
                }
            }
        }
    }

    /**
     * Re-applied on every configuration change, which is what makes a foldable work: the inner screen
     * unfolds into a large one and landscape becomes available on the spot.
     *
     * Only reached when the activity is *not* recreated (it is not, once a manifest `configChanges` is
     * added later); harmless either way, because [applyOrientationPolicy] is idempotent.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy()
    }

    /**
     * **Landscape is for tablets and unfolded foldables only.**
     *
     * On a phone the player and the feed are designed around a tall window: rotated, the mini-player and
     * the bottom navigation together eat most of a ~400dp-tall viewport and there is no room left for the
     * content they float over. Rather than ship a sideways layout nobody would use, the app simply stays
     * upright on phone-shaped devices and offers every orientation where there is room for one.
     *
     * The test is the **smallest** screen edge, not the current width — a phone turned sideways reports a
     * tablet-sized width, so keying off the current width would defeat the whole thing. `FULL_USER` on the
     * large screens respects the user's own rotation lock instead of overriding it.
     */
    private fun applyOrientationPolicy() {
        val large = resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_SW_DP
        requestedOrientation = if (large) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

/**
 * How long the completed lockup holds before dissolving into the app. Deliberately short: the system
 * splash has already shown the mark for the whole cold start, so this stage only has to complete the logo.
 *
 * Cut from 750 ms: with the Home now rendering from its disk cache, this hold *was* the longest thing
 * between launching the app and using it — an animation waiting on nothing.
 */
private const val SPLASH_HOLD_MS = 250L

/**
 * Asks for notifications once, on first launch.
 *
 * The permission was declared in the manifest but never requested, so both notifications this app posts
 * were silently invisible: the media controls and the download progress. Denial costs nothing — playback
 * and downloads both still run (the services stay foreground either way), you just don't see them.
 *
 * minSdk is 34, so this permission always applies; no version guard needed.
 */
@androidx.compose.runtime.Composable
private fun AskForNotificationsOnce() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
