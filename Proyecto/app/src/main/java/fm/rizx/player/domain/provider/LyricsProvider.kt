package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.Track

/**
 * A lyrics provider (Phase 15): fetches plain-text lyrics for a [Track]. A [ProviderDescriptor] of
 * kind [ProviderKind.LYRICS], so it lives in the registry and is single-active. Returns `null` when
 * no lyrics are found (a normal outcome, not an error); transport failures raise a typed error.
 */
interface LyricsProvider : ProviderDescriptor {
    suspend fun getLyrics(track: Track): String?
}
