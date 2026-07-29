package fm.rizx.player.data.plugin

import fm.rizx.player.data.plugin.engine.PluginException
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `api.Ytdlp` for the plugin sandbox, backed by the app's own NewPipe-based YouTube extractor instead
 * of a yt-dlp binary Android cannot run (ADR 0019). Speaks the yt-dlp-ish JSON dialect the registry's
 * YouTube plugins actually read — `search` entries carry `id/title/duration/thumbnail`, `getStream`
 * returns `{stream_url, duration, container, codec}`, `getPlaylist` returns
 * `{id, title, entries: [{id, title, channel, duration, thumbnails}]}` (verified against
 * nuclear-plugin-youtube and nuclear-plugin-youtube-playlists sources).
 *
 * NewPipe is blocking network, so every op dispatches to IO.
 */
class YtdlpFacade(
    private val youtube: YoutubeExtractorClient,
    private val json: Json,
) {
    suspend fun handle(op: String, argJson: String): String = withContext(Dispatchers.IO) {
        val args = runCatching { json.parseToJsonElement(argJson).jsonObject }.getOrNull()
            ?: throw PluginException("Ytdlp.$op: bad arguments")
        when (op) {
            "search" -> search(args["query"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "getStream" -> getStream(args["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "getPlaylist" -> getPlaylist(args["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
            else -> throw PluginException("Ytdlp.$op: unknown operation")
        }
    }

    private fun search(query: String): String {
        if (query.isBlank()) return "[]"
        val items = youtube.searchSongs(query, SEARCH_LIMIT)
        return buildJsonArray {
            for (item in items) {
                val id = videoIdOf(item.url) ?: continue
                add(buildJsonObject {
                    put("id", id)
                    put("title", item.name ?: id)
                    put("duration", item.duration) // seconds, yt-dlp style
                    put("channel", item.uploaderName ?: "")
                    item.thumbnails.firstOrNull()?.url?.let { put("thumbnail", it) }
                    put("webpage_url", item.url)
                })
            }
        }.toString()
    }

    private fun getStream(idOrUrl: String): String {
        if (idOrUrl.isBlank()) throw PluginException("Ytdlp.getStream: empty id")
        val url = if (idOrUrl.startsWith("http")) idOrUrl else "https://www.youtube.com/watch?v=$idOrUrl"
        val info = youtube.streamInfo(url)
        val audio = info.audioStreams.maxByOrNull { it.averageBitrate }
            ?: throw PluginException("Ytdlp.getStream: no audio for $idOrUrl")
        return buildJsonObject {
            put("stream_url", audio.content)
            put("duration", info.duration) // seconds
            audio.format?.let {
                put("container", it.name.lowercase())
                put("codec", it.suffix)
            }
            put("bitrate", audio.averageBitrate)
        }.toString()
    }

    private fun getPlaylist(url: String): String {
        if (url.isBlank()) throw PluginException("Ytdlp.getPlaylist: empty url")
        val data = youtube.playlist(url)
        val listId = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: url
        return buildJsonObject {
            put("id", listId)
            put("title", data.name ?: listId)
            put("entries", buildJsonArray {
                for (item in data.items) {
                    val id = videoIdOf(item.url) ?: continue
                    add(buildJsonObject {
                        put("id", id)
                        put("title", item.name ?: id)
                        // The playlists plugin filters out null channels — always give it one.
                        put("channel", item.uploaderName ?: data.uploaderName ?: "YouTube")
                        put("duration", item.duration)
                        put("thumbnails", buildJsonArray {
                            item.thumbnails.firstOrNull()?.url?.let { add(buildJsonObject { put("url", it) }) }
                        })
                    })
                }
            })
        }.toString()
    }

    private fun videoIdOf(url: String?): String? =
        url?.let { Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(it)?.groupValues?.get(1) }
            ?: url?.let { Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(it)?.groupValues?.get(1) }

    private companion object {
        const val SEARCH_LIMIT = 15
    }
}
