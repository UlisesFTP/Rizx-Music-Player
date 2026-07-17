package fm.rizx.player.domain.provider

/**
 * Categories of provider the app can register. Upstream models this as an open string set; the
 * MVP uses a closed enum of the kinds we actually dispatch to. Only [METADATA] and [STREAMING]
 * are exercised before Phase 13; the rest are reserved for later capabilities.
 */
enum class ProviderKind {
    METADATA,
    STREAMING,
    LYRICS,
    DASHBOARD,
    PLAYLISTS,
    DISCOVERY,
}
