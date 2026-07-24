package fm.rizx.player

import android.Manifest
import android.content.pm.PackageManager
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
import fm.rizx.player.ui.theme.RizxTheme
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
}

/**
 * How long the completed lockup holds before dissolving into the app. Deliberately short: the system
 * splash has already shown the mark for the whole cold start, so this stage only has to complete the logo.
 */
private const val SPLASH_HOLD_MS = 750L

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
