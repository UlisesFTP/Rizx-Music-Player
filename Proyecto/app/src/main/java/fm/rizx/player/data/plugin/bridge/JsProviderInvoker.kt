package fm.rizx.player.data.plugin.bridge

/**
 * The single chokepoint through which every bridge calls plugin JS (ADR 0019). The runtime's
 * implementation owns the eval statement, the per-call timeout, and the per-plugin
 * consecutive-failure counter that quarantines a misbehaving plugin — so no bridge can forget any
 * of the three.
 */
interface JsProviderInvoker {
    /**
     * Invoke [method] on the provider registered as [uid] (`pluginId:descriptorId`) with a JSON-array
     * [argsJson]. Returns the JSON-encoded result, or null when the method resolved to nothing.
     * Throws [fm.rizx.player.data.plugin.engine.PluginException] on a JS error or timeout.
     */
    suspend fun invoke(uid: String, method: String, argsJson: String, timeoutMs: Long = 15_000): String?
}
