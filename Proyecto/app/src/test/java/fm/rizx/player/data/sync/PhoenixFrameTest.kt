package fm.rizx.player.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire protocol, version 1.0.0, as the invalidation channel speaks it. */
class PhoenixFrameTest {

    @Test
    fun `a private join carries the token and asks for no self echo`() {
        val obj = Json.parseToJsonElement(PhoenixFrame.join("realtime:user:u1:sync", "1", "jwt").encode()).jsonObject

        assertEquals("phx_join", obj["event"]!!.jsonPrimitive.content)
        assertEquals("realtime:user:u1:sync", obj["topic"]!!.jsonPrimitive.content)
        assertEquals("1", obj["ref"]!!.jsonPrimitive.content)
        assertEquals("1", obj["join_ref"]!!.jsonPrimitive.content)
        val payload = obj["payload"]!!.jsonObject
        assertEquals("jwt", payload["access_token"]!!.jsonPrimitive.content)
        val config = payload["config"]!!.jsonObject
        assertTrue(config["private"]!!.jsonPrimitive.boolean)
        assertFalse(config["broadcast"]!!.jsonObject["self"]!!.jsonPrimitive.boolean)
        assertFalse(config["presence"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a heartbeat goes on the phoenix topic without a join ref`() {
        val obj = Json.parseToJsonElement(PhoenixFrame.heartbeat("7").encode()).jsonObject

        assertEquals("phoenix", obj["topic"]!!.jsonPrimitive.content)
        assertEquals("heartbeat", obj["event"]!!.jsonPrimitive.content)
        assertEquals("7", obj["ref"]!!.jsonPrimitive.content)
        assertNull("absent, not null", obj["join_ref"])
    }

    @Test
    fun `the server's sync broadcast decodes into an invalidation`() {
        val frame = PhoenixFrame.decode(
            """{"topic":"realtime:user:u1:sync","event":"broadcast","payload":{"event":"sync","type":"broadcast",""" +
                """"meta":{"id":"006554ce"},"payload":{"revision":2119,"device_id":"dev-B"}},"ref":null}""",
        )!!

        assertEquals(SyncInvalidation(2119, "dev-B"), frame.invalidation())
    }

    @Test
    fun `other broadcasts, replies and junk are not invalidations`() {
        val chat = PhoenixFrame.decode("""{"topic":"realtime:user:u1:sync","event":"broadcast","payload":{"event":"hello","payload":{"revision":1}}}""")!!
        assertNull(chat.invalidation())
        val refused = PhoenixFrame.decode("""{"topic":"realtime:user:u1:sync","event":"phx_reply","payload":{"status":"error","response":{"reason":"Unauthorized: no"}},"ref":"1"}""")!!
        assertNull(refused.invalidation())
        assertEquals("error", refused.replyStatus)
        assertEquals("Unauthorized: no", refused.reason)
        val ok = PhoenixFrame.decode("""{"topic":"phoenix","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"7"}""")!!
        assertEquals("ok", ok.replyStatus)
        assertNull(ok.reason)
        assertNull(PhoenixFrame.decode("not json"))
    }

    @Test
    fun `an invalidation is news when it is past the cursor and from another device`() {
        assertTrue(SyncInvalidation(5, "b").isRelevant(ownDeviceId = "a", localCursor = 4))
        assertTrue("no device stated: assume news", SyncInvalidation(5, null).isRelevant("a", 4))
        assertFalse("own echo", SyncInvalidation(5, "a").isRelevant("a", 4))
        assertFalse("already pulled", SyncInvalidation(4, "b").isRelevant("a", 4))
    }
}
