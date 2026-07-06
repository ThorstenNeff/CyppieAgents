package com.tneff.cyppieagents.contract

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-234a-2a — the generated **AsyncAPI (WS) document**: structure + the `/ws/hub` frontend-exclusion made
 * TESTABLE (design §2.5), and the **bidirectional WS-channel drift-test** — every real WS socket is EITHER a
 * frontend channel OR a KNOWN exclusion (no silently-missing channel; no phantom channel).
 */
class AsyncApiContractTest {

    // The actual WS socket inventory (the `webSocket("…")` routes across the routing package). Stable set; if a
    // socket is added, this + the AsyncAPI (or the exclude list) must both update → the partition test catches it.
    private val actualWsSockets = setOf("/ws/comm", "/ws/events", "/ws/lifecycle", "/ws/agent", "/ws/hub")

    private fun doc() = ContractGenerator.asyncApi()
    private fun channels() = doc()["channels"] as JsonObject
    private fun schemas() = (doc()["components"] as JsonObject)["schemas"] as JsonObject
    private fun messages() = (doc()["components"] as JsonObject)["messages"] as JsonObject

    @Test
    fun asyncApi_hasTheFrontendChannels_withMessagesRefencingGeneratedSchemas() {
        val ch = channels()
        assertEquals(setOf("/ws/comm", "/ws/events", "/ws/lifecycle", "/ws/agent"), ch.keys, "exactly the frontend channels")
        // /ws/comm carries CommWsServerEvent (server→client) + CommWsClientEvent (client→server)
        val comm = ch["/ws/comm"] as JsonObject
        val sub = ((comm["subscribe"] as JsonObject)["message"] as JsonObject)["\$ref"] as JsonPrimitive
        assertEquals("#/components/messages/CommWsServerEvent", sub.content)
        assertTrue((comm["publish"] as JsonObject).containsKey("message"), "client→server publish present")
        // every message payload $ref resolves to a generated schema component
        for ((_, m) in messages()) {
            val ref = ((m as JsonObject)["payload"] as JsonObject)["\$ref"] as JsonPrimitive
            val name = ref.content.substringAfterLast('/')
            assertTrue(schemas().containsKey(name), "message payload '$name' resolves to a generated schema")
        }
        // /ws/lifecycle is server→client only (no publish)
        assertFalse((ch["/ws/lifecycle"] as JsonObject).containsKey("publish"), "lifecycle is a one-way status feed")
    }

    @Test
    fun wsHub_isExcluded_notSilentlyMissing() {
        assertFalse(channels().containsKey("/ws/hub"), "/ws/hub is NOT a frontend channel")
        assertTrue("/ws/hub" in ContractGenerator.EXCLUDED_WS_PATHS, "…but it is a KNOWN, explicit exclusion (connector wire)")
    }

    @Test
    fun wsChannelDrift_isBidirectional_everySocketIsChannelOrKnownExclusion() {
        val documented = channels().keys + ContractGenerator.EXCLUDED_WS_PATHS
        // no real socket missing from the union (documented OR knowingly-excluded)…
        assertEquals(emptySet(), actualWsSockets - documented, "every real WS socket is a channel or a known exclusion")
        // …and no phantom channel/exclusion that isn't a real socket
        assertEquals(emptySet(), documented - actualWsSockets, "no documented channel/exclusion without a real socket")
    }
}
