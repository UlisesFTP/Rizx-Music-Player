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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr

/** Prompts for a name and creates a new (empty) playlist. */
@Composable
fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Prompts for a playlist URL (Deezer / Rizx export) and imports it read-only (Phase 22). */
@Composable
fun ImportUrlDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import playlist from URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("Paste a playlist URL…") },
                )
                Text(
                    "Spotify · YouTube Music · YouTube · Deezer, or a link to an exported playlist file.",
                    style = mr(12, FontWeight.Medium),
                    color = RizxTheme.colors.muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(url); onDismiss() }, enabled = url.isNotBlank()) { Text("Import from URL") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks *where* to import from. One "Import" entry point beats two look-alike icons in the header — the
 * user picks a source in words instead of decoding 🔗 vs ⬇.
 */
@Composable
fun ImportSourceDialog(onUrl: () -> Unit, onFile: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImportSourceOption(
                    title = "From a link",
                    subtitle = "Spotify · YouTube Music · YouTube · Deezer",
                    onClick = onUrl,
                )
                ImportSourceOption(
                    title = "From a file",
                    subtitle = "A Rizx, Nuclear or Exportify export",
                    onClick = onFile,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text(if (creating) "New playlist" else "Add to playlist") },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("Playlist name") },
                    )
                } else if (playlists.isEmpty()) {
                    Text("No playlists yet — create one below.", style = mr(14, FontWeight.Medium), color = c.muted)
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
                TextButton(onClick = { onCreateNew(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Create & add") }
            } else {
                TextButton(onClick = { creating = true }) { Text("New playlist") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
