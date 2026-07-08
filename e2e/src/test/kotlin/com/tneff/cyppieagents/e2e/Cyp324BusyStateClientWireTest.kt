package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.agentview.BusyStateLiveSource
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-324 INTEGRATION gate (my QA tooth) — the **real client [BusyStateLiveSource]** driven against the **real
 * server `/ws/busy-state` route + real [com.tneff.cyppieagents.boot.AgentBusyStateTracker]** on [e2ePlatform].
 *
 * Anti-fake-shape (the exact class that almost got CYP-325): Dev's two real halves are never stitched —
 * the UI `BusyStateClientTest` runs the real source against a HAND-ROLLED embedded ws route, and the server
 * `BusyStateSocketTest` runs the real route against a RAW ws client (hand-decoded frames). Both happen to match
 * because both call `CommJson.encodeToString/decodeFromString(AgentBusyStateEvent.serializer())` on the shared
 * `:core` contract — but neither PROVES the real source connects to, authenticates against, and decodes the REAL
 * route end-to-end. This closes it over the real socket (same pattern as [Cyp316TokenUsageClientWireTest]):
 *  - **Z1 busy:** turn-start in the real tracker → real snapshot frame → real source decodes `busy = true`.
 *  - **Z2 idle delta:** turn-end → a live `busy = false` reaches the real source (latest-wins), the `*` clears.
 *  - **Z3 reconnect-mid-busy:** a fresh source re-streams the snapshot `busy = true` — no hanging/lost `*`.
 *  - **Z4 content-free over the real wire:** the real route's frame carries ONLY `{agentId, busy}` (never any body).
 *
 * (The busy render `*` and its token-indicator coexistence over the real `WindowHost` is Dev's `WindowBusyRenderTest`;
 * `BusyStateViewModel` extends `androidx.lifecycle.ViewModel`, invisible to `:e2e` — same split as CYP-315/316: this
 * proves the real source→wire→route seam and upserts by `agentId` exactly as the VM does.)
 */
class Cyp324BusyStateClientWireTest {

    private fun boot() = listOf(
        SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
    )

    /** A bare ws client (only the WebSockets plugin) so the source's own `?token=` query-param auth is the real
     *  handshake against the real route — exactly the production path. */
    private fun wsClient() = HttpClient(CIO) { install(ClientWebSockets) }

    private fun source(p: E2ePlatform, client: HttpClient, agentId: String = "backend") =
        BusyStateLiveSource(client, p.wsBaseUrl, token = E2ePlatform.agentToken(agentId))

    /** Z1 — a real turn-start busy value flows through the real route + real source snapshot as decoded `busy=true`. */
    @Test
    fun realSource_decodesBusyTrue_overRealSocket(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            p.booted.runtimeRegistry.active().busyState.set("backend", true) // seed BEFORE connect → in snapshot
            val client = wsClient()
            try {
                val ev = withTimeout(10_000) { source(p, client).events().first { it.agentId == "backend" } }
                assertEquals(true, ev.busy, "the real client decodes busy=true off the real /ws/busy-state wire")
            } finally { client.close() }
        }
    }

    /** Z4 — the real route's busy frame is content-free (ONLY {agentId, busy}), decoded by the real source's serializer. */
    @Test
    fun realBusyFrame_isContentFree_overRealWire(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            p.booted.runtimeRegistry.active().busyState.set("backend", true)
            wsClient().use { raw ->
                withTimeout(10_000) {
                    raw.webSocket("${p.wsBaseUrl}/ws/busy-state?token=${E2ePlatform.agentToken("backend")}") {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val ev = CommJson.decodeFromString(AgentBusyStateEvent.serializer(), text)
                            if (ev.agentId != "backend") continue
                            val keys = CommJson.parseToJsonElement(text).jsonObject.keys
                            assertTrue(keys.all { it == "agentId" || it == "busy" }, "busy-state frame is content-free: $keys")
                            return@webSocket
                        }
                    }
                }
            }
        }
    }

    /** Z2 — turn-end idle: a live busy→idle delta reaches the real source (latest-wins), so the `*` clears. */
    @Test
    fun realSource_busyThenIdle_liveDelta_overRealSocket(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            val tracker = p.booted.runtimeRegistry.active().busyState
            tracker.set("backend", true)
            val client = wsClient()
            val seen = mutableListOf<AgentBusyStateEvent>()
            val collector = launch {
                source(p, client).events().filter { it.agentId == "backend" }.collect { seen.add(it) }
            }
            try {
                withTimeout(10_000) { while (seen.none { it.busy }) delay(20) }
                tracker.set("backend", false) // turn-end → idle
                withTimeout(10_000) { while (seen.none { !it.busy }) delay(20) }
                assertEquals(
                    false, seen.last { it.agentId == "backend" }.busy,
                    "turn-end → the real source sees busy=false (idle); the title-bar `*` clears",
                )
            } finally { collector.cancel(); client.close() }
        }
    }

    /** Z3 — reconnect MID-busy: a fresh source's connect snapshot re-delivers busy=true (no hanging/lost `*`). */
    @Test
    fun realReconnect_midBusy_resnapshotsBusyTrue_overRealSocket(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            p.booted.runtimeRegistry.active().busyState.set("backend", true)
            suspend fun connectAndReadBusy(): Boolean {
                val client = wsClient()
                return try {
                    withTimeout(10_000) { source(p, client).events().first { it.agentId == "backend" }.busy }
                } finally { client.close() }
            }
            assertEquals(true, connectAndReadBusy(), "initial connect: busy=true")
            assertEquals(true, connectAndReadBusy(), "reconnect mid-busy: snapshot re-delivers busy=true — no stuck/lost `*`")
        }
    }
}
