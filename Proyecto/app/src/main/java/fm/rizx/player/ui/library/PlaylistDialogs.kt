package fm.rizx.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.model.PlaylistSummary
import androidx.compose.ui.graphics.RectangleShape
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr

/** Prompts for a name and creates a new (empty) playlist. */
@Composable
fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_playlist)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.dialog_playlist_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name); onDismiss() }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Imports a playlist, from a link **or** a file, in one step.
 *
 * Previously this was two dialogs — one asking *where from*, another asking *what* — so pasting a link cost
 * three taps. The link field is by far the common case, so it's here immediately and the file picker is a
 * secondary row underneath; nobody has to answer a question before they can start.
 */
@Composable
fun ImportPlaylistDialog(onImport: (String) -> Unit, onFile: () -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = { Text(stringResource(R.string.dialog_import_playlist_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.dialog_paste_playlist_link_hint)) },
                )
                Text(
                    "Spotify · YouTube Music · YouTube · Deezer",
                    style = mr(12, FontWeight.Medium),
                    color = RizxTheme.colors.muted,
                )
                ImportSourceOption(
                    title = stringResource(R.string.dialog_import_from_file_title),
                    subtitle = stringResource(R.string.dialog_import_from_file_subtitle),
                    onClick = onFile,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(url); onDismiss() }, enabled = url.isNotBlank()) { Text(stringResource(R.string.action_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ImportSourceOption(title: String, subtitle: String, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.98f, pressColor = c.rowHover, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = mr(14, FontWeight.SemiBold), color = c.text)
        Text(subtitle, style = mr(12, FontWeight.Medium), color = c.muted)
    }
}

/** Confirms a destructive action that can't be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Picks an existing playlist to add a track to, or creates a new one with the track. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistSummary>,
    onPick: (String) -> Unit,
    onCreateNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) stringResource(R.string.dialog_new_playlist) else stringResource(R.string.dialog_add_to_playlist_title)) },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.dialog_playlist_name_hint)) },
                    )
                } else if (playlists.isEmpty()) {
                    Text(stringResource(R.string.dialog_no_playlists_yet), style = mr(14, FontWeight.Medium), color = c.muted)
                } else {
                    playlists.forEach { playlist ->
                        Text(
                            playlist.name,
                            style = mr(15, FontWeight.SemiBold),
                            color = c.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist.id); onDismiss() }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = { onCreateNew(name); onDismiss() }, enabled = name.isNotBlank()) { Text(stringResource(R.string.dialog_create_and_add)) }
            } else {
                TextButton(onClick = { creating = true }) { Text(stringResource(R.string.dialog_new_playlist)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
