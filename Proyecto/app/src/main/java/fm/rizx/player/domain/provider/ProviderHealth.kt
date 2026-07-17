package fm.rizx.player.domain.provider

/** Health/latency of a provider as shown in the Plugins screen (Phase 21). */
sealed interface ProviderHealth {
    data class Ok(val latencyMs: Long) : ProviderHealth
    data class Down(val reason: String) : ProviderHealth
    data object Unknown : ProviderHealth
}
