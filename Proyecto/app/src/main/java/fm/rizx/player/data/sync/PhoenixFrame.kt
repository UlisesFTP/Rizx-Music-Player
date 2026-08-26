package fm.rizx.player.data.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One message of Supabase Realtime's wire protocol, version `1.0.0`: a JSON object with `topic`,
 * `event`, `payload`, `ref` and, for anything bound to a joined channel, `join_ref`. Only the handful
 * of events the invalidation channel needs are modelled; everything else is decoded and ignored.
 */
@Serializable
data class PhoenixFrame(
    val topic: String,
    val event: String,
    val payload: JsonElement = JsonObject(emptyMap()),
    val ref: String? = null,
    @SerialName("join_ref") val joinRef: String? = null,
) {
    fun encode(): String = WIRE.encodeToString(serializer(), this)

    /** `"ok"` or `"error"` on a `phx_reply`; null for anything else. */
    val replyStatus: String? get() = (payload as? JsonObject)?.get("status")?.string

    /** The server's reason on a refused join or a `system` error, when it gave one. */
    val reason: String?
        get() {
            val body = payload as? JsonObject ?: return null
            val response = body["response"] as? JsonObject
            return response?.get("reason")?.string ?: response?.get("error")?.string ?: body["message"]?.string
        }

    /** The sync nudge carried by a `broadcast` frame, or null when this frame is something else. */
    fun invalidation(): SyncInvalidation? {
        if (event != BROADCAST) return null
        val body = payload as? JsonObject ?: return null
        if (body["event"]?.string != SYNC_EVENT) return null
        val data = body["payload"] as? JsonObject ?: return null
        val revision = data["revision"]?.let { it as? JsonPrimitive }?.longOrNull ?: return null
        return SyncInvalidation(revision, data["device_id"]?.string)
    }

    private val JsonElement.string: String? get() = (this as? JsonPrimitive)?.contentOrNull

    companion object {
        const val HEARTBEAT_TOPIC = "phoenix"
        const val JOIN = "phx_join"
        const val LEAVE = "phx_leave"
        const val REPLY = "phx_reply"
        const val ERROR = "phx_error"
        const val CLOSE = "phx_close"
        const val SYSTEM = "system"
        const val BROADCAST = "broadcast"
        const val ACCESS_TOKEN = "access_token"

        /** The event name the server's `rizx_sync` sends on the account channel. */
        const val SYNC_EVENT = "sync"

        @OptIn(ExperimentalSerializationApi::class)
        private val WIRE = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        fun decode(text: String): PhoenixFrame? = runCatching { WIRE.decodeFromString(serializer(), text) }.getOrNull()

        /** `realtime:` is the server's namespace; what follows is what `realtime.topic()` sees in RLS. */
        fun syncTopic(userId: String): String = "realtime:user:$userId:sync"

        fun heartbeat(ref: String) = PhoenixFrame(HEARTBEAT_TOPIC, "heartbeat", JsonObject(emptyMap()), ref)

        /** A private join: the token is what RLS on `realtime.messages` judges, and no presence or self-echo. */
        fun join(topic: String, ref: String, accessToken: String) = PhoenixFrame(
            topic = topic,
            event = JOIN,
            payload = buildJsonObject {
                putJsonObject("config") {
                    putJsonObject("broadcast") { put("self", false); put("ack", false) }
                    putJsonObject("presence") { put("enabled", false) }
                    put("postgres_changes", JsonArray(emptyList()))
                    put("private", true)
                }
                put("access_token", accessToken)
            },
            ref = ref,
            joinRef = ref,
        )

        /** Refreshes the channel's JWT in place; without it the server drops the channel at expiry. */
        fun accessToken(topic: String, ref: String, joinRef: String, token: String) = PhoenixFrame(
            topic, ACCESS_TOKEN, buildJsonObject { put("access_token", token) }, ref, joinRef,
        )

        fun leave(topic: String, ref: String, joinRef: String) = PhoenixFrame(topic, LEAVE, JsonObject(emptyMap()), ref, joinRef)
    }
}
