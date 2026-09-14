package fm.rizx.player.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * The hand-off to Android's package installer. Outside Google Play an app cannot update itself
 * silently: it can only present the APK to the system installer, which asks the user to confirm and
 * then replaces the app (and restarts it). Two things are on the app: holding
 * `REQUEST_INSTALL_PACKAGES` (manifest) and having been allowed to install unknown apps once, which
 * is a per-app system setting the user grants on a screen this class opens.
 *
 * The APK is offered through the app's FileProvider (`<applicationId>.files`, `updates/`), never as a
 * bare file path, which API 24+ refuses.
 */
class AppUpdateInstaller(private val context: Context) {

    /** Whether Android will accept an install request from this app right now. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the user allows this app to install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the installer on [apk]. The user sees Android's own "update this app?" sheet. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val AUTHORITY_SUFFIX = ".files"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
