package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray

/**
 * Bridges a JS-registered lyrics provider into the native [LyricsProvider] contract (ADR 0019). No
 * registry plugin ships one yet; the bridge exists so the kind stops being silently dropped and a
 * community lyrics plugin works the day it appears. Accepts `getLyrics`/`fetchLyrics(track)` returning
 * a string, `{lyrics|plain|text}`, or `{lines: [{timeMs, text}]}`.
 */
class JsLyricsProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val methods: Set<String>,
    private val invoker: JsProviderInvoker,
    private val json: Json,
) : LyricsProvider {

    override val kind: ProviderKind = ProviderKind.LYRICS

    override suspend fun getLyrics(track: Track): Lyrics? {
        val method = when {
            "getLyrics" in methods -> "getLyrics"
            "fetchLyrics" in methods -> "fetchLyrics"
            else -> return null
        }
        val args = buildJsonArray { add(JsModelMappers.trackToJson(track)) }.toString()
        val result = invoker.invoke(uid, method, args) ?: return null
        return JsModelMappers.parseLyrics(result, sourceName = name, json = json)
    }
}
