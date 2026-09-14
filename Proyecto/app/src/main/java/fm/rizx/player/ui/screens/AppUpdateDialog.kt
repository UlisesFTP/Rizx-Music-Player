package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fm.rizx.player.R
import fm.rizx.player.data.download.formatBytes
import fm.rizx.player.domain.update.AppUpdate
import fm.rizx.player.domain.update.AppUpdateFailure
import fm.rizx.player.domain.update.AppUpdateState
import fm.rizx.player.ui.components.Editorial
import fm.rizx.player.ui.components.EditorialButton
import fm.rizx.player.ui.components.SignalEyebrow
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/** The short value the Settings row shows for the update [state]. */
@Composable
fun appUpdateRowValue(state: AppUpdateState): String = when (state) {
    AppUpdateState.Unknown -> stringResource(R.string.update_value_check)
    AppUpdateState.Checking -> stringResource(R.string.update_value_checking)
    is AppUpdateState.UpToDate -> stringResource(R.string.update_value_up_to_date)
    is AppUpdateState.Available -> stringResource(R.string.update_value_available, state.update.versionName)
    is AppUpdateState.Downloading -> state.fraction?.let { stringResource(R.string.update_value_downloading, (it * 100).toInt()) }
        ?: stringResource(R.string.update_value_downloading_unknown)
    is AppUpdateState.Ready -> stringResource(R.string.update_value_ready)
    is AppUpdateState.Failed -> stringResource(R.string.update_value_failed)
}

/**
 * The update dialog (spec 024): what is published, what changed, how big it is, and the one next
 * step — download, install, allow installs, retry — in the editorial dialog chrome the other Settings
 * pickers use. "Skip this version" is always a way out; "Later" just closes it.
 *
 * [canInstall] is re-read every time the app comes back on screen, because the only way to grant the
 * install permission is to leave for a system page and return.
 */
@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    installedVersion: String,
    onMobileData: Boolean,
    canInstall: () -> Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onInstall: (filePath: String) -> Unit,
    onAllowInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    var installAllowed by remember { mutableStateOf(canInstall()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { installAllowed = canInstall() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(c.elev)
                .border(1.5.dp, c.hardLine)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            SignalEyebrow(stringResource(R.string.update_dialog_eyebrow))
            val update = state.current
            Text(
                if (update != null) stringResource(R.string.update_available_title, update.versionName)
                else stringResource(R.string.update_dialog_title),
                style = sg(22, FontWeight.Bold, -0.01f),
                color = c.text,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            when (state) {
                AppUpdateState.Unknown -> Body(stringResource(R.string.update_unknown))
                AppUpdateState.Checking -> Body(stringResource(R.string.update_checking))
                is AppUpdateState.UpToDate -> Body(
                    state.skippedVersion?.let { stringResource(R.string.update_skipped, it, installedVersion) }
                        ?: stringResource(R.string.update_up_to_date, installedVersion),
                )
                is AppUpdateState.Available -> {
                    ReleaseSummary(state.update, installedVersion, onMobileData)
                    ReleaseNotes(state.update)
                }
                is AppUpdateState.Downloading -> {
                    ReleaseSummary(state.update, installedVersion, onMobileData)
                    val fraction = state.fraction
                    Body(
                        if (state.totalBytes > 0) stringResource(R.string.update_downloading, formatBytes(state.downloadedBytes), formatBytes(state.totalBytes))
                        else stringResource(R.string.update_downloading_unknown, formatBytes(state.downloadedBytes)),
                    )
                    ProgressBar(fraction)
                }
                is AppUpdateState.Ready -> {
                    ReleaseSummary(state.update, installedVersion, onMobileData = false)
                    Body(stringResource(R.string.update_ready))
                    if (!installAllowed) {
                        Body(stringResource(R.string.update_allow_install_caption), muted = true)
                    }
                }
                is AppUpdateState.Failed -> Body(
                    when (state.reason) {
                        AppUpdateFailure.NETWORK -> stringResource(R.string.update_failed_network)
                        AppUpdateFailure.VERIFICATION -> stringResource(R.string.update_failed_verification)
                        AppUpdateFailure.UNKNOWN -> stringResource(R.string.update_failed_unknown)
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    AppUpdateState.Unknown, is AppUpdateState.UpToDate ->
                        EditorialButton(stringResource(R.string.update_check_again), onCheck, Modifier.weight(1f), primary = true, dense = true)
                    AppUpdateState.Checking -> Unit
                    is AppUpdateState.Available -> {
                        EditorialButton(stringResource(R.string.update_download), onDownload, Modifier.weight(1f), primary = true, dense = true)
                        EditorialButton(stringResource(R.string.update_skip), onSkip, Modifier.weight(1f), dense = true)
                    }
                    is AppUpdateState.Downloading ->
                        EditorialButton(stringResource(R.string.update_cancel), onCancelDownload, Modifier.weight(1f), dense = true)
                    is AppUpdateState.Ready -> {
                        if (installAllowed) {
                            EditorialButton(stringResource(R.string.update_install), { onInstall(state.filePath) }, Modifier.weight(1f), primary = true, dense = true)
                        } else {
                            EditorialButton(stringResource(R.string.update_allow_install), onAllowInstall, Modifier.weight(1f), primary = true, dense = true)
                        }
                        EditorialButton(stringResource(R.string.update_skip), onSkip, Modifier.weight(1f), dense = true)
                    }
                    is AppUpdateState.Failed ->
                        EditorialButton(stringResource(R.string.update_retry), onRetry, Modifier.weight(1f), primary = true, dense = true)
                }
            }
            Text(
                stringResource(R.string.update_later).uppercase(),
                style = mr(12, FontWeight.SemiBold, 0.08f),
                color = c.muted,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clickableScale(scale = 0.97f, onClick = onDismiss)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    val c = RizxTheme.colors
    Text(text, style = mr(14, FontWeight.Medium, lineHeight = 21), color = if (muted) c.muted else c.text2, modifier = Modifier.padding(bottom = 6.dp))
}

/** Size, the installed version for comparison, and the mobile-data warning when it applies. */
@Composable
private fun ReleaseSummary(update: AppUpdate, installedVersion: String, onMobileData: Boolean) {
    val c = RizxTheme.colors
    val size = if (update.apkBytes > 0) formatBytes(update.apkBytes) else update.apkName
    Text(
        stringResource(R.string.update_available_size, size, installedVersion),
        style = mr(12, FontWeight.Medium, 0.02f),
        color = c.muted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (onMobileData) {
        Text(stringResource(R.string.update_mobile_data), style = mr(12, FontWeight.SemiBold), color = c.redAccent, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** The release body as plain lines: headings lose their `#`, list markers become bullets, the rest is as written. */
@Composable
private fun ReleaseNotes(update: AppUpdate) {
    val c = RizxTheme.colors
    val lines = remember(update.notes) { plainNotes(update.notes) }
    if (lines.isEmpty()) {
        Body(stringResource(R.string.update_notes_empty), muted = true)
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(Editorial.Hairline, c.line)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        lines.forEach { line ->
            Text(line, style = mr(13, FontWeight.Normal, lineHeight = 19), color = c.text)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float?) {
    val c = RizxTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(12.dp)
            .border(1.5.dp, c.hardLine),
    ) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction).height(12.dp).background(c.redAccent))
        }
    }
}

private const val MAX_NOTE_LINES = 14

internal fun plainNotes(notes: String): List<String> {
    val lines = notes.lines()
        .map { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") -> line.trimStart('#').trim()
                line.startsWith("- ") || line.startsWith("* ") -> "• " + line.substring(2).trim()
                else -> line
            }
        }
        .filter { it.isNotBlank() }
    return if (lines.size <= MAX_NOTE_LINES) lines else lines.take(MAX_NOTE_LINES) + "…"
}
