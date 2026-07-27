package com.tneff.cyppieagents.net

import com.tneff.cyppieagents.net.hub.pool.REST_DEDICATED_CONNS
import com.tneff.cyppieagents.net.hub.pool.TUNNEL_POOL_CAP
import com.tneff.cyppieagents.net.hub.pool.WS_RESERVED_SLOTS
import io.ktor.client.engine.cio.EndpointConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-610 (the 6-agent-remote root fix) — the load-bearing tooth for the REST-isolation, **deterministic by
 * construction** (no socket/timing race).
 *
 * The root: the loopback tunnel pool is blind to WS-vs-REST, so idle keep-alive REST connections starve the
 * [WS_RESERVED_SLOTS] persistent WS out of the `min(cap, rendezvousSet)` usable ids → the 1-up/6-churn. The fix caps
 * the REST loopback client to [REST_DEDICATED_CONNS] connections-per-route ([applyRestLoopbackCap]) so the dozen+ REST
 * repos share that many warm keep-alive sockets = ≤ that many Noise tunnels, mathematically unable to claim a WS slot.
 *
 * **Why a config tooth, not a behavioural socket test:** the CIO connection count under a *cold simultaneous burst* is
 * inherently non-deterministic (its check-then-create is not atomic → a brief handoff connection can appear — the
 * "peak=2" a loaded env saw; bounded, absorbed by the cap-16 DATA-lane headroom, never a durable WS-slot theft). Asserting on a
 * sampled peak is therefore env-flaky, and a widened tolerance would just paper over the race. Instead this asserts the
 * **configuration** that governs the cap — exact, deterministic, and it reddens the instant the cap line is removed
 * (CIO's default `maxConnectionsPerRoute` is 100 = effectively uncapped). The behaviour is proven END-TO-END on the real
 * wire by the QA harness's C7 remote-isolation flow in the guided verify run (the authoritative behavioural proof).
 */
class Cyp610RestConnectionCapTest {

    @Test
    fun restLoopbackConfig_capsConnectionsPerRoute_toRestDedicatedConns() {
        // Sanity: a fresh CIO endpoint is effectively UNcapped (this is what the WS client keeps — each WS its own socket).
        val fresh = EndpointConfig()
        assertEquals(100, fresh.maxConnectionsPerRoute, "precondition: CIO's default maxConnectionsPerRoute is 100 (uncapped)")

        // The fix: the REST endpoint config caps connections-per-route to the dedicated budget + keeps the socket warm.
        val rest = EndpointConfig().apply { applyRestLoopbackCap() }
        assertEquals(
            REST_DEDICATED_CONNS, rest.maxConnectionsPerRoute,
            "REST must be capped to REST_DEDICATED_CONNS=$REST_DEDICATED_CONNS connections/route (one shared keep-alive " +
                "socket) so it holds ≤ that many tunnels — removing the cap reverts to CIO's uncapped default (100)",
        )
        assertEquals(LOOPBACK_KEEP_ALIVE_MS, rest.keepAliveTime, "the one REST socket stays warm (∞ keep-alive), no dial-per-request churn")
        assertTrue(
            REST_DEDICATED_CONNS < fresh.maxConnectionsPerRoute,
            "REST's cap must be far below the uncapped WS default — REST can never fan out to its own per-request sockets",
        )
    }

    @Test
    fun partition_fitsUsableDataBudgetAfterControl() {
        // WS + 1 REST must fit the usable data-ids (cap − 1 control). Post-CYP-846 (status-mux 4→1) the WS working set
        // is 11; the coherent cap=16 + margin envelope is pinned separately in Cyp611ClientCapEnvelopeTest.
        val usable = TUNNEL_POOL_CAP - 1 // Control tunnel holds rendezvous-id 0
        assertTrue(
            WS_RESERVED_SLOTS + REST_DEDICATED_CONNS <= usable,
            "partition ${WS_RESERVED_SLOTS}WS + ${REST_DEDICATED_CONNS}REST must fit $usable usable ids (cap $TUNNEL_POOL_CAP − 1 control)",
        )
        assertEquals(1, REST_DEDICATED_CONNS, "REST is one shared keep-alive socket (Backend: one tunnel = one SOCKET)")
        assertTrue(WS_RESERVED_SLOTS >= 11, "the 7-agent default needs ≥11 WS (7 agent + 4 singleton after the CYP-846 status-mux: status/comm/events/acl)")
        assertTrue(REST_DEDICATED_CONNS < WS_RESERVED_SLOTS, "REST's budget must be ≪ the WS reservation")
    }
}
