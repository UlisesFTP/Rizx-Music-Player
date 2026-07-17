package fm.rizx.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.rizx.player.core.formatDuration
import fm.rizx.player.domain.model.PlaybackQueue
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.library.CreatePlaylistDialog
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

/**
 * The playback queue as a scrollable list. Observes `QueueViewModel.queue`; tapping an item makes it
 * current (highlighted), the trailing button removes it, and "Clear" empties the queue. Actual audio
 * playback is Phase 8 — for now selecting an item only moves the cursor.
 */
@Composable
fun QueueScreen(
    queue: PlaybackQueue,
    onBack: () -> Unit,
    onPlayItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onClear: () -> Unit,
    onSaveAsPlaylist: (String) -> Unit,
) {
    val c = RizxTheme.colors
    var saving by remember { mutableStateOf(false) }
    if (saving) {
        CreatePlaylistDialog(onCreate = onSaveAsPlaylist, onDismiss = { saving = false })
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                RizxIcons.Back,
                "Back",
                tint = c.text,
                modifier = Modifier.size(26.dp).clickableScale(scale = 0.88f, onClick = onBack),
            )
            Text("Queue", style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.weight(1f))
            if (queue.items.isNotEmpty()) {
                Icon(
                    RizxIcons.PlaylistAdd,
                    "Save as playlist",
                    tint = c.text2,
                    modifier = Modifier.size(24.dp).clickableScale(scale = 0.86f, onClick = { saving = true }),
                )
                Text(
                    "Clear",
                    style = mr(13, FontWeight.SemiBold),
                    color = c.text2,
                    modifier = Modifier.clickableScale(scale = 0.92f, onClick = onClear),
                )
            }
        }

        val count = queue.items.size
        Text(
            if (count == 0) "Nothing queued" else "$count ${if (count == 1) "track" else "tracks"}",
            style = mr(13, FontWeight.Medium),
            color = c.muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        if (queue.items.isEmpty()) {
            EmptyQueue()
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(queue.items, key = { _, item -> item.id }) { index, item ->
                    Box(Modifier.staggeredReveal(index)) {
                        QueueRow(
                            position = index + 1,
                            item = item,
                            isCurrent = index == queue.currentIndex,
                            onPlay = { onPlayItem(item.id) },
                            onRemove = { onRemoveItem(item.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun QueueRow(
    position: Int,
    item: QueueItem,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Icon(RizxIcons.Play, "Now current", tint = c.accent, modifier = Modifier.size(20.dp))
            } else {
                Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
            }
        }
        CoverArt(tintFor(item.track.source.id), initial = null, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.track.title,
                style = mr(14, FontWeight.SemiBold),
                color = if (isCurrent) c.accent else c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.track.artists.joinToString { it.name }.ifEmpty { "Unknown artist" },
                style = mr(12, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatDuration(item.track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
        Icon(
            RizxIcons.Close,
            "Remove from queue",
            tint = c.text2,
            modifier = Modifier.size(22.dp).clickableScale(scale = 0.84f, onClick = onRemove),
        )
    }
}

@Composable
private fun EmptyQueue() {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your queue is empty", style = sg(18, FontWeight.Bold), color = c.text)
            Text(
                "Add songs from Search to line them up here.",
                style = mr(13, FontWeight.Medium),
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
