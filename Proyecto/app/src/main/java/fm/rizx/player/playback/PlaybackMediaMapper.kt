package fm.rizx.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.coverUrl

/**
 * Pure, framework-free description of what to hand ExoPlayer for a queue item. Splitting this out
 * from [MediaItem] construction keeps the mapping (especially `mediaId == QueueItem.id`) unit-testable
 * without Android.
 */
data class MediaSpec(
    val mediaId: String,
    val uri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/**
 * Maps a resolved [queueItem] + its [stream] to a [MediaSpec]. The `mediaId` is the **[QueueItem.id]**
 * (not the track's `ProviderRef`) so the engine's current-item id ties back to the exact queue entry,
 * even when the same track appears multiple times (AGENTS.md).
 */
fun toMediaSpec(queueItem: QueueItem, stream: Stream): MediaSpec {
    val track = queueItem.track
    return MediaSpec(
        mediaId = queueItem.id,
        uri = stream.url,
        title = track.title,
        artist = track.artists.joinToString { it.name }.ifEmpty { null },
        album = track.album?.title,
        durationMs = track.durationMs ?: stream.durationMs,
    )
}

// ---- Placeholder URIs for the player timeline (just-in-time resolution) ----
//
// The player holds the whole queue as MediaItems so notification / lock-screen next-previous work
// natively, but their URIs are opaque placeholders — the real (ephemeral) stream URL is resolved on
// demand by a ResolvingDataSource when the item is about to play, so URLs are never persisted.

const val QUEUE_ITEM_URI_SCHEME = "rizx"
private const val QUEUE_ITEM_URI_PREFIX = "$QUEUE_ITEM_URI_SCHEME://queue/"

/** The opaque timeline URI for a queue item id (resolved just-in-time to the real stream). */
fun queueItemPlaceholderUri(queueItemId: String): String = QUEUE_ITEM_URI_PREFIX + queueItemId

/** Extracts the queue item id from a placeholder URI, or null if it isn't one. */
fun queueItemIdFromPlaceholder(uri: String): String? =
    if (uri.startsWith(QUEUE_ITEM_URI_PREFIX)) {
        uri.removePrefix(QUEUE_ITEM_URI_PREFIX).takeIf { it.isNotEmpty() }
    } else {
        null
    }

/**
 * Builds the timeline [MediaItem] for a queue item: `mediaId = QueueItem.id`, an opaque placeholder
 * URI (resolved just-in-time), and [MediaMetadata] (title/artist/album/artwork) for the media
 * notification.
 *
 * The artwork is what the system draws the whole media panel from — the cover itself, and the colours
 * it tints the card, lock screen, Bluetooth and watch with. Without it the notification is a bare
 * grey slab, which is exactly what it was until this carried [MediaMetadata.artworkUri].
 */
fun QueueItem.toTimelineMediaItem(): MediaItem {
    val t = track
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(queueItemPlaceholderUri(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artists.joinToString { it.name }.ifEmpty { null })
                .setAlbumTitle(t.album?.title)
                .setArtworkUri(t.artwork.coverUrl()?.let(Uri::parse))
                .build(),
        )
        .build()
}
