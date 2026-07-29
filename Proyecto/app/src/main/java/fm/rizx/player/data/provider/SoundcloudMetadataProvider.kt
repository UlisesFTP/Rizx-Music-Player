package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.remote.soundcloud.SoundcloudIds
import fm.rizx.player.data.remote.soundcloud.toSoundcloudArtistOrNull
import fm.rizx.player.data.remote.soundcloud.toSoundcloudTrackOrNull
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * **SoundCloud** as a selectable metadata source, over NewPipe (keyless — NewPipe scrapes the public
 * `client_id` itself, exactly as it already does for the Underground tab).
 *
 * Tracks come back with SoundCloud-native refs, so tapping one short-circuits straight to the
 * SoundCloud streaming provider instead of re-searching (`StreamingRepositoryImpl`'s native-owner
 * path). No albums: SoundCloud's model is tracks, playlists and users, and inventing albums from
 * playlists would put wrong things behind an "Albums" tab.
 */
class SoundcloudMetadataProvider(
    private val client: SoundcloudExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MetadataProvider {

    override val id: String = SoundcloudIds.METADATA
    override val kind: ProviderKind = ProviderKind.METADATA
    override val name: String = "SoundCloud"
    override val version: String = "1.0"
    override val searchCapabilities: Set<SearchCapability> =
        setOf(SearchCapability.UNIFIED, SearchCapability.TRACKS, SearchCapability.ARTISTS)

    /**
     * Both namespaces: search rows are minted as `soundcloud` (the streaming catalogue id, so a tap
     * short-circuits straight to playback) while artist refs use this provider's own id.
     */
    override val ownedNamespaces: Set<String> = setOf(SoundcloudIds.STREAMING, SoundcloudIds.METADATA)

    // No trackDetail: resolving one SoundCloud track by permalink costs a full page extraction, and
    // its artwork is an avatar or a waveform anyway — the very thing POOR_ARTWORK_PROVIDERS asks to
    // replace. The default (null) sends these tracks down the verified cross-catalogue path instead.

    override suspend fun search(params: SearchParams): SearchResults {
        val query = params.query.trim()
        if (query.isEmpty()) return SearchResults()
        val limit = params.limit ?: DEFAULT_LIMIT
        val types = params.types
        return guarded {
            withContext(io) {
                SearchResults(
                    artists = if (types == null || SearchCategory.ARTISTS in types) {
                        client.searchUsers(query, limit).mapNotNull { it.toSoundcloudArtistOrNull() }
                    } else emptyList(),
                    tracks = if (types == null || SearchCategory.TRACKS in types) {
                        client.searchTracks(query, limit).mapNotNull { it.toSoundcloudTrackOrNull() }
                    } else emptyList(),
                )
            }
        }
    }

    /** SoundCloud's own related-tracks endpoint — a real recommendation, not a re-search. */
    override suspend fun radioTracks(seed: Track): List<Track> {
        val trackUrl = seed.source.takeIf { it.provider == SoundcloudIds.STREAMING }?.let { it.url ?: it.id }
            ?: resolveSeedUrl(seed)
            ?: return emptyList()
        return guarded {
            withContext(io) {
                client.related(trackUrl, RADIO_LIMIT).mapNotNull { it.toSoundcloudTrackOrNull() }
                    .filter { it.source != seed.source }
            }
        }
    }

    /** A non-SoundCloud seed has to be found on SoundCloud first; its best match seeds the radio. */
    private suspend fun resolveSeedUrl(seed: Track): String? = runCatching {
        withContext(io) {
            val query = listOfNotNull(seed.artists.firstOrNull()?.name, seed.title).joinToString(" ")
            client.searchTracks(query, 1).firstOrNull()?.url
        }
    }.getOrNull()

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw AppError.Network(e.message ?: "connection failed", e)
    } catch (e: Exception) {
        throw AppError.ProviderFailure(name, e.message ?: "SoundCloud request failed", e)
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val RADIO_LIMIT = 25
    }
}
