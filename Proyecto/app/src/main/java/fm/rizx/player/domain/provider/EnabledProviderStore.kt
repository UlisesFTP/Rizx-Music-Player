package fm.rizx.player.domain.provider

import kotlinx.coroutines.flow.Flow

/**
 * Per-provider **enabled** state (Phase 21), distinct from the registry's single-active selection.
 * Persisted so a user's on/off choices survive restarts. Providers default to **enabled**. Used to
 * filter the fan-out kinds (dashboard, playlists) — a disabled provider stops contributing.
 */
interface EnabledProviderStore {
    fun isEnabled(id: String): Flow<Boolean>
    suspend fun setEnabled(id: String, enabled: Boolean)
    /** Current on/off snapshot for the given ids (defaults to enabled when unset). */
    suspend fun snapshot(ids: Collection<String>): Map<String, Boolean>
}
