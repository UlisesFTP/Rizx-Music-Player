package fm.rizx.player

import fm.rizx.player.domain.model.SpatialRender
import fm.rizx.player.domain.model.SpatialRenderState
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.SpatialRenderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Nothing rendered, nothing rendering — the counterpart to [NoDownloads], for the ViewModels that take a
 * [SpatialRenderRepository] but whose tests are about something else.
 */
class NoSpatialRenders : SpatialRenderRepository {
    override val renders: StateFlow<Map<String, SpatialRender>> = MutableStateFlow(emptyMap())
    override val states: StateFlow<Map<String, SpatialRenderState>> = MutableStateFlow(emptyMap())
    override fun render(track: Track) = Unit
    override fun cancel(key: String) = Unit
    override suspend fun delete(key: String) = Unit
}
