package fm.rizx.player.ui.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Storage-permission plumbing for "save downloads to the phone": permission-free on API 29+ (scoped
 * storage) but gated by the dangerous `WRITE_EXTERNAL_STORAGE` on 26–28.
 *
 * Returns a runner — `ensure { export() }` — that executes immediately when no permission is involved
 * (or it was already granted) and otherwise launches the system dialog, running the queued action only
 * on grant; [onDenied] fires on refusal and the action is dropped. Kept at composable level on
 * purpose: the repositories that do the copying are JVM-tested and must never see `SDK_INT`, and the
 * automatic post-download copy can't prompt anyway — by the time it runs, this gate was already passed
 * at opt-in (the first-download dialog, the Settings toggle, or a row's save button).
 */
@Composable
fun rememberSaveToPhonePermission(onDenied: () -> Unit = {}): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending.value
        pending.value = null
        if (granted) action?.invoke() else onDenied()
    }
    return remember(launcher, context) {
        { action: () -> Unit ->
            val free = Build.VERSION.SDK_INT >= 29 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (free) {
                action()
            } else {
                pending.value = action
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}
