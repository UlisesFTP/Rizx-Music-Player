package fm.rizx.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.thumbnailUrl

/**
 * Whether artwork should be fetched at the cheap size.
 *
 * A `CompositionLocal` rather than a parameter threaded through every screen: which rung to load is one
 * app-wide policy, and the fifty-odd places that show a cover have no business knowing about the network.
 * Provided once, near the root, from the data-saver state.
 *
 * `static` because it changes about as often as the user opens Settings; a read of it is not worth
 * tracking for recomposition on every image in a scrolling grid.
 */
val LocalThriftyArtwork = staticCompositionLocalOf { false }

/**
 * The cover URL for a **grid or list tile**, at whichever size the current policy allows.
 *
 * Roughly a tenth of the bytes when saving is on (measured against Deezer: 22-87 KB for the standard
 * tile rung versus 8-28 KB for the thrifty one), and on a 120dp tile the difference is not visible.
 *
 * Deliberately **not** used by Now Playing, the notification or the tag writer: those show one image
 * each, full size or written into a file, and shrinking them saves nothing worth the quality.
 */
@Composable
@ReadOnlyComposable
fun ArtworkSet?.tileUrl(): String? =
    if (LocalThriftyArtwork.current) thumbnailUrl() else coverUrl()
