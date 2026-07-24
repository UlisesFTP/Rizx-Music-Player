package fm.rizx.player.data.remote.deezer

/**
 * Fetches **every** track of a Deezer playlist, not just the first page.
 *
 * `/playlist/{id}` embeds `tracks.data` but stops at 400 — and, unlike Deezer's other list endpoints,
 * hands back **no `next` link**, so nothing about the response says it was cut. A 1640-track playlist
 * imported as 400 and looked complete. `nb_tracks` is the only honest signal, and `/playlist/{id}/tracks`
 * is the endpoint that actually pages.
 *
 * The already-embedded tracks are reused as the first page, so an ordinary playlist (under 400, which is
 * nearly all of them) still costs exactly one request.
 */
internal suspend fun DeezerApi.allPlaylistTracks(
    playlistId: String,
    embedded: List<DeezerTrackDto>,
    declaredTotal: Int?,
): List<DeezerTrackDto> {
    val total = declaredTotal ?: return embedded
    if (embedded.size >= total) return embedded

    val wanted = minOf(total, MAX_TRACKS)
    val all = ArrayList<DeezerTrackDto>(wanted)
    all += embedded
    while (all.size < wanted) {
        // Ask only for what's still missing. Requesting a full page regardless would overshoot the cap
        // (and, on an ordinary playlist, pull hundreds of rows past the end for nothing).
        val page = playlistTracks(playlistId, index = all.size, limit = minOf(PAGE_SIZE, wanted - all.size))
        val data = page.data
        // An empty page means the playlist shrank under us, or Deezer stopped early. Either way,
        // continuing would spin forever on the same index.
        if (data.isEmpty()) break
        all += data
    }
    return all
}

/** Deezer accepts far larger pages, but 500 keeps any single response modest on a phone. */
private const val PAGE_SIZE = 500

/**
 * A ceiling so a pathological playlist can't page indefinitely. Well past anything a person curates —
 * the largest found while testing was 1640.
 */
private const val MAX_TRACKS = 5_000
