package fm.rizx.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fm.rizx.player.ui.RizxApp
import fm.rizx.player.ui.player.PlayerViewModel
import fm.rizx.player.ui.theme.RizxTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The activity-scoped PlayerViewModel drives both the live theme and playback.
            val playerViewModel: PlayerViewModel = hiltViewModel()
            val state by playerViewModel.state.collectAsStateWithLifecycle()

            AskForNotificationsOnce()

            RizxTheme(darkTheme = state.isDark) {
                RizxApp(playerViewModel = playerViewModel)
            }
        }
    }
}

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
