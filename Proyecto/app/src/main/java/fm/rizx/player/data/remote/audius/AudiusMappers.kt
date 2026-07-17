package fm.rizx.player.data.remote.audius

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol

/** Registry ids + constants for the Audius provider. */
object AudiusIds {
    const val STREAMING = "audius-streaming"
    const val APP_NAME = "RizxPlayer"
}

/**
 * Pure DTO → domain mappers for Audius. Candidates carry the Audius track id as identity; the concrete
 * **full-length** stream URL is `"{host}/v1/tracks/{id}/stream?app_name=…"` (Audius returns a 302 to a CDN
 * file, which ExoPlayer follows). Stream URLs are ephemeral — resolved just-in-time, never persisted.
 */

/** Phase-1 candidate (identity only; no stream URL yet). Returns null if the row lacks an id/title. */
fun AudiusTrackDto.toStreamCandidateOrNull(): StreamCandidate? {
    val trackId = id ?: return null
    val name = title ?: return null
    return StreamCandidate(
        id = trackId,
        title = name,
        durationMs = duration?.let { it.toLong() * 1000 },
        thumbnail = artwork?.let { it.large ?: it.medium ?: it.small },
        source = ProviderRef(AudiusIds.STREAMING, trackId),
    )
}

/**
 * True when this Audius row plausibly **is** the requested track. Guards against Audius's loose
 * full-text search returning unrelated songs for mainstream queries: without this, a query for
 * "Shakira – Dai Dai" (absent from Audius) returns fuzzy junk that would then play as the wrong song
 * (or fail), instead of letting the resolver fall back to another provider.
 *
 * Rule: normalized titles must be equal or one must contain the other; when both artist names are
 * known, at least one meaningful artist token must overlap (or one contain the other). When the row
 * carries no artist, the title match alone is accepted.
 */
fun AudiusTrackDto.matches(wantTitle: String, wantArtist: String?): Boolean {
    val gotTitle = normalizeMatch(title)
    val want = normalizeMatch(wantTitle)
    if (gotTitle.isEmpty() || want.isEmpty()) return false
    val titleOk = gotTitle == want || gotTitle.contains(want) || want.contains(gotTitle)
    if (!titleOk) return false

    val gotArtist = normalizeMatch(user?.name)
    val wantA = normalizeMatch(wantArtist)
    if (gotArtist.isEmpty() || wantA.isEmpty()) return true // can't compare artists → accept on title
    if (gotArtist.contains(wantA) || wantA.contains(gotArtist)) return true
    val gotTokens = gotArtist.split(' ').toSet()
    return wantA.split(' ').any { it.length > 2 && gotTokens.contains(it) }
}

/** Lowercase, drop parenthetical/bracketed qualifiers (remix, feat, …), keep alphanumerics only. */
private fun normalizeMatch(s: String?): String =
    (s ?: "")
        .lowercase()
        .replace(Regex("[(\\[].*?[)\\]]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

/** Phase-2 concrete stream for [candidateId] against the currently-chosen [host]. */
fun audiusStream(host: String, candidateId: String, durationMs: Long?): Stream = Stream(
    url = "$host/v1/tracks/$candidateId/stream?app_name=${AudiusIds.APP_NAME}",
    protocol = StreamProtocol.HTTPS,
    mimeType = "audio/mpeg",
    codec = "mp3",
    container = "mp3",
    qualityLabel = "Full track",
    durationMs = durationMs,
    source = ProviderRef(AudiusIds.STREAMING, candidateId),
)
