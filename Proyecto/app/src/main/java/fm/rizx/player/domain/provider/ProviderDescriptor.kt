package fm.rizx.player.domain.provider

/**
 * The minimal identity/category of any registered provider. Concrete providers (metadata,
 * streaming, …) implement this and add their capability methods in later phases. [id] is the
 * global registry key; [pluginId] names the owning plugin once a plugin runtime exists (post-MVP).
 */
interface ProviderDescriptor {
    val id: String
    val kind: ProviderKind
    val name: String
    val pluginId: String? get() = null
    /** Provider version, shown in the Plugins screen (Phase 21). Defaults so existing providers compile. */
    val version: String get() = "1.0"
}
