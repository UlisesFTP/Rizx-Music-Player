package fm.rizx.player.ui.model

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Lightweight UI-shell sample models. These stand in for real domain models until the
 * fake-provider data path lands (see ROADMAP Phase 2+). They intentionally carry only
 * what the Rizx screens render. `tintIndex` selects the per-cover gradient hue.
 */

data class Mix(val name: String, val sub: String, val tintIndex: Int) {
    val initial: String get() = name.take(1)
}

data class AlbumCard(val name: String, val meta: String, val tintIndex: Int) {
    val initial: String get() = name.take(1)
}

data class ArtistCard(val name: String, val meta: String, val tintIndex: Int) {
    val initial: String get() = name.take(1)
}

data class LibraryItem(
    val name: String,
    val meta: String,
    val liked: Boolean,
    val tintIndex: Int,
) {
    val initial: String get() = name.take(1)
}

data class TrackRow(
    val name: String,
    val artist: String,
    val dur: String,
    val playing: Boolean,
    val tintIndex: Int,
)

data class Device(
    val name: String,
    val meta: String,
    val icon: ImageVector,
    val active: Boolean,
)

data class MusicSource(val name: String, val meta: String, val icon: ImageVector)

data class BrowseCategory(val label: String, val tintIndex: Int)
