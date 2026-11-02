package fm.rizx.player.data.local.store

import fm.rizx.player.domain.discovery.DiscoveryFeedback
import fm.rizx.player.domain.discovery.HiddenItems
import fm.rizx.player.domain.discovery.TasteSeeds
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class StoredDiscovery(
    val hiddenArtists: List<String> = emptyList(),
    val hiddenPlaylists: List<String> = emptyList(),
    val seeds: List<String> = emptyList(),
    val seedsDismissed: Boolean = false,
)

/**
 * The listener's discovery preferences on this device: what they said "not interested" to, and the
 * artists they picked to start from.
 *
 * One JSON blob written atomically, `Mutex`-serialized and `runCatching`-guarded — the idiom of
 * [SearchHistoryStore] and [HomeFeedStore], for the same reason: a corrupt file degrades to "nothing
 * hidden, no seeds", never to a broken Home. **Local only** for now; the sync server has no table for it
 * (spec 026 lists that as follow-up).
 *
 * Hidden lists are capped at [MAX_HIDDEN] each, oldest dropped first, so a habit of hiding things never
 * grows the file without bound.
 */
class DiscoveryFeedbackStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DiscoveryFeedback, TasteSeeds {

    private val json get() = TrackJson.json
    private val lock = Mutex()
    private val state = MutableStateFlow(StoredDiscovery())
    private var loaded = false

    private val live: Flow<StoredDiscovery> = state.onStart { ensureLoaded() }

    override val hidden: Flow<HiddenItems> = live.map {
        HiddenItems(artistKeys = it.hiddenArtists.toSet(), playlistKeys = it.hiddenPlaylists.toSet())
    }

    override val seeds: Flow<List<String>> = live.map { it.seeds }
    override val dismissed: Flow<Boolean> = live.map { it.seedsDismissed }

    override suspend fun hideArtist(name: String) {
        val key = ArtistNameMatching.key(name).takeIf { it.isNotEmpty() } ?: return
        update { it.copy(hiddenArtists = (it.hiddenArtists - key + key).takeLast(MAX_HIDDEN)) }
    }

    override suspend fun hidePlaylist(identityKey: String) {
        if (identityKey.isBlank()) return
        update { it.copy(hiddenPlaylists = (it.hiddenPlaylists - identityKey + identityKey).takeLast(MAX_HIDDEN)) }
    }

    override suspend fun unhideArtist(name: String) {
        val key = ArtistNameMatching.key(name)
        update { it.copy(hiddenArtists = it.hiddenArtists - key) }
    }

    override suspend fun unhidePlaylist(identityKey: String) =
        update { it.copy(hiddenPlaylists = it.hiddenPlaylists - identityKey) }

    override suspend fun clear() = update { it.copy(hiddenArtists = emptyList(), hiddenPlaylists = emptyList()) }

    override suspend fun setSeeds(names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { ArtistNameMatching.key(it) }
            .take(MAX_SEEDS)
        update { it.copy(seeds = clean, seedsDismissed = it.seedsDismissed || clean.isNotEmpty()) }
    }

    override suspend fun dismiss() = update { it.copy(seedsDismissed = true) }

    private suspend fun update(change: (StoredDiscovery) -> StoredDiscovery) {
        ensureLoaded()
        val next = lock.withLock { change(state.value).also { state.value = it } }
        withContext(io) { runCatching { write(next) } }
    }

    /** Not reentrant with [update]'s lock section — a Kotlin `Mutex` is not reentrant. */
    private suspend fun ensureLoaded() {
        lock.withLock {
            if (loaded) return
            loaded = true
            state.value = withContext(io) {
                runCatching {
                    if (!file.exists()) return@runCatching StoredDiscovery()
                    json.decodeFromString(StoredDiscovery.serializer(), file.readText())
                }.getOrDefault(StoredDiscovery())
            }
        }
    }

    private fun write(value: StoredDiscovery) {
        val text = json.encodeToString(StoredDiscovery.serializer(), value)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val MAX_HIDDEN = 500
        const val MAX_SEEDS = 10
    }
}
