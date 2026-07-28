package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.agentview.StatusMuxClient
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StatusFrame
import com.tneff.cyppieagents.model.TokenUsageStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-316 INTEGRATION gate (my QA tooth) — the **real client [TokenUsageLiveSource]** driven against the **real
 * server `/ws/token-usage` route + real `AgentTokenUsageTracker`** on [e2ePlatform]. Dev's two real halves are
 * never stitched: `TokenUsageClientTest` runs the real client source against a HAND-ROLLED ws route, and
 * `TokenUsageSocketTest` runs the real route against a RAW ws client (hand-decoded frames). Neither proves the real
 * client source connects to, authenticates against, and decodes the real route end-to-end — the CYP-315 fake-shape
 * class. This closes it over the real socket:
 *  - **Z1 number:** a value in the real tracker → real snapshot frame → real source decodes the real Int.
 *  - **⭐ Z2 null ≠ 0 over the real wire:** a `null` value → the server OMITS `contextTokens` (`explicitNulls=false`,
 *    the exact CYP-310 omit-on-wire mechanic) → the real source decodes `null` (never a `0`, never a MissingField throw).
 *  - **restart → null live:** a REAL `POST /api/agents/{id}/restart` → lifecycle `onContextReset` → `tracker.reset`
 *    → the real source receives a live `null` delta (fresh context).
 *  - **reconnect idempotent:** a second connection re-streams the snapshot; the upsert-by-`agentId` fold is
 *    unchanged (no dup / no loss / no stale).
 *
 * (The VM itself extends `androidx.lifecycle.ViewModel`, invisible to `:e2e` — same split as CYP-315: this proves the
 * real source→wire seam and folds events exactly as `TokenUsageViewModel` does; the title-bar render Z1/Z2 is Dev's
 * `WindowContextTokensRenderTest` over the real `WindowHost`.)
 */
class Cyp316TokenUsageClientWireTest {

    private fun boot() = listOf(
        SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
    )

    /** A bare ws client (only the WebSockets plugin, NO default bearer) so the source's own `?token=` query-param
     *  auth path is what authenticates against the real route — exactly the production handshake. */
    private fun wsClient() = HttpClient(CIO) { install(ClientWebSockets) }

    // CYP-846: the standalone `/ws/token-usage` `TokenUsageLiveSource` was REMOVED — the live token-usage source is
    // now the muxed `/ws/status` projection [StatusMuxClient.tokenUsage] (the interface + [AgentTokenUsageEvent]
    // shape are unchanged, so the source→wire→decode seam this gate proves is identical, now over `/ws/status`).
    private fun source(p: E2ePlatform, client: HttpClient, agentId: String = "backend") =
        StatusMuxClient(
            client,
            httpBaseUrl = p.baseUrl,
            wsBaseUrl = p.wsBaseUrl,
            token = E2ePlatform.agentToken(agentId),
            // A SupervisorJob scope for the mux's shared `/ws/status` upstream. WhileSubscribed → the upstream
            // stops when this test's collector does; the socket is closed via [client] in each test's finally.
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        ).tokenUsage

    /** Z1 — a real tracker value flows through the real route + real source snapshot as the decoded Int. */
    @Test
    fun realSource_decodesContextTokenNumber_overRealSocket(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            p.booted.runtimeRegistry.active().tokenUsage.onResult("backend", 137_000) // seed BEFORE connect → in snapshot
            val client = wsClient()
            try {
                val ev = withTimeout(10_000) { source(p, client).events().first { it.agentId == "backend" } }
                assertEquals(137_000, ev.contextTokens, "the real client decodes the server-resolved token count off the real wire")
            } finally { client.close() }
        }
    }

    /**
     * ⭐ Z2 — `null ≠ 0` over the REAL wire. A null value: the server encodes the frame with `contextTokens` OMITTED
     * (`CommJson.explicitNulls=false`, the same omit-on-wire that broke CYP-310), and the real source must decode it
     * back to `null` — never `0`, never a MissingField throw. Proven twice: (a) the raw frame has NO `contextTokens`
     * key; (b) the real source yields `contextTokens == null`.
     */
    @Test
    fun realSource_nullContextTokens_survivesRealWire_neverZero(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            p.booted.runtimeRegistry.active().tokenUsage.onResult("backend", null) // Connector-B / pre-first-turn
            // (a) the raw muxed frame OMITS the field (explicitNulls) — non-vacuous evidence of the wire mechanic.
            // CYP-846: the wire is now the muxed `/ws/status` `TokenUsageStatus` frame ({"type":"tokenUsage",
            // "event":{…}}); the null-omit happens INSIDE the wrapped [AgentTokenUsageEvent] exactly as before.
            wsClient().use { raw ->
                withTimeout(10_000) {
                    raw.webSocket("${p.wsBaseUrl}/ws/status?token=${E2ePlatform.agentToken("backend")}") {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val sf = CommJson.decodeFromString(StatusFrame.serializer(), text)
                            if (sf !is TokenUsageStatus || sf.event.agentId != "backend") continue
                            val eventKeys = CommJson.parseToJsonElement(text).jsonObject["event"]!!.jsonObject.keys
                            assertTrue("contextTokens" !in eventKeys, "a null value OMITS contextTokens inside the muxed event: $eventKeys")
                            assertNull(sf.event.contextTokens)
                            return@webSocket
                        }
                    }
                }
            }
            // (b) the real source decodes it back to null (never 0).
            val client = wsClient()
            try {
                val ev = withTimeout(10_000) { source(p, client).events().first { it.agentId == "backend" } }
                assertNull(ev.contextTokens, "null contextTokens survives the omit-on-wire round-trip — never coerced to 0")
            } finally { client.close() }
        }
    }

    /**
     * restart → null LIVE over the real socket. Seed a value, connect, receive it, then fire a REAL operator
     * restart — the lifecycle's `onContextReset` → `tracker.reset` must push a live `null` delta to the real source.
     */
    @Test
    fun realRestart_pushesLiveNull_toRealSource(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            p.booted.runtimeRegistry.active().tokenUsage.onResult("backend", 90_000)
            val client = wsClient()
            val seen = mutableListOf<AgentTokenUsageEvent>()
            val collector = launch {
                source(p, client).events().filter { it.agentId == "backend" }.collect { seen.add(it) }
            }
            try {
                withTimeout(10_000) { while (seen.isEmpty()) delay(20) }
                assertEquals(90_000, seen.first().contextTokens, "the seeded standing context arrives first")

                p.asOperator().use { op -> op.post("${p.baseUrl}/api/agents/backend/restart") } // REAL lifecycle restart

                withTimeout(10_000) { while (seen.none { it.contextTokens == null }) delay(20) }
                assertNull(
                    seen.first { it.contextTokens == null }.contextTokens,
                    "restart clears the standing context → a live null delta reaches the real client (fresh context)",
                )
            } finally { collector.cancel(); client.close() }
        }
    }

    /**
     * Reconnect idempotency — a fresh connection re-streams the snapshot; the upsert-by-agentId fold (exactly what
     * `TokenUsageViewModel` does) is unchanged: no dup, no loss, no stale. Two agents carry values so the fold is real.
     */
    @Test
    fun realReconnect_snapshotReupsert_isIdempotent(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            val tracker = p.booted.runtimeRegistry.active().tokenUsage
            tracker.onResult("backend", 50_000)
            tracker.onResult("po", 12_000)

            suspend fun connectAndFoldSnapshot(): Map<String, Int?> {
                val client = wsClient()
                return try {
                    withTimeout(10_000) {
                        source(p, client).events()
                            .filter { it.agentId == "backend" || it.agentId == "po" }
                            .take(2) // each agent appears once in the snapshot
                            .toList()
                            .associate { it.agentId to it.contextTokens } // the VM's upsert-by-agentId, distilled
                    }
                } finally { client.close() }
            }

            val first = connectAndFoldSnapshot()   // initial connect
            val second = connectAndFoldSnapshot()   // simulated reconnect → fresh snapshot
            assertEquals(mapOf("backend" to 50_000, "po" to 12_000), first)
            assertEquals(first, second, "a reconnect snapshot re-delivers the SAME state — idempotent upsert, no dup/loss/stale")
        }
    }
}
