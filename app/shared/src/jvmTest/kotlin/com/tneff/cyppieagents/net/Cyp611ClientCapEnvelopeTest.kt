package com.tneff.cyppieagents.net

import com.tneff.cyppieagents.net.hub.pool.CONTROL_RESERVED_SLOTS
import com.tneff.cyppieagents.net.hub.pool.REST_DEDICATED_CONNS
import com.tneff.cyppieagents.net.hub.pool.TUNNEL_POOL_CAP
import com.tneff.cyppieagents.net.hub.pool.WS_RESERVED_SLOTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-611 (client-first cap revert 24→16) — the **coherent-cap-envelope** tooth. Deterministic by construction (pure
 * partition arithmetic, no socket/timing race), the sibling of [Cyp610RestConnectionCapTest].
 *
 * The envelope must hold together, not just the cap number: reverting [TUNNEL_POOL_CAP] to 16 is only correct because
 * **CYP-846** muxed the four per-agent status feeds (lifecycle/token-usage/busy/terminal-state) into ONE `/ws/status`,
 * dropping the WS working set 14 → [WS_RESERVED_SLOTS]=11 (7 agent + 4 singleton: status/comm/events/acl). So the
 * DATA (WS) lane at cap=16 — `usable(15) − [CONTROL_RESERVED_SLOTS](2) = 13` — still fits `11 WS + 1 REST` with a
 * reconnect-overlap **margin**, keeping the CYP-610 zero-headroom edge (the 1-up/6-churn root) closed at the LOWER cap.
 *
 * This is the guard against an incoherent bump: dropping the cap without the mux (WS still 14 → 14+1 > 13), or a mux
 * without the cap-follow, would break the partition. Pinning cap==16 catches a revert to 24 (`cap→24` = RED); the
 * margin invariant catches wrong slot values (`WS_RESERVED_SLOTS` left at 14 → `15 > 13` = RED).
 *
 * SCOPE: **client only.** Backend's `DEFAULT_TUNNEL_POOL_CAP` stays 24 until a PL-sequenced Backend2 change follows;
 * transiently server(24) ≥ client(16) is the required invariant (the client self-limits via `min(cap, rendezvousSet)`),
 * so this tooth does not assert the server value.
 */
class Cyp611ClientCapEnvelopeTest {

    @Test
    fun clientCap_isReverted_to16() {
        // The ticket's deliverable + the mutation anchor: a revert to 24 (or any other value) reddens here directly.
        assertEquals(16, TUNNEL_POOL_CAP, "CYP-611: the CLIENT tunnel-pool cap is reverted 24→16 (server follows, PL-sequenced)")
    }

    @Test
    fun partition_fitsDataLane_withReconnectMargin_atCap16() {
        val usable = TUNNEL_POOL_CAP - 1                    // id 0 = the CP control tunnel (drop(1))
        val dataLane = usable - CONTROL_RESERVED_SLOTS      // the WS lane, after the break-glass CONTROL reserve
        val wsPlusRest = WS_RESERVED_SLOTS + REST_DEDICATED_CONNS

        assertEquals(2, CONTROL_RESERVED_SLOTS, "CYP-616/PL: 2 control slots (one break-glass + one REST reconnect-overlap)")
        assertEquals(
            11, WS_RESERVED_SLOTS,
            "measured post-CYP-846 WS working set = 7 agent-WS + 4 singleton-WS (status/comm/events/acl); the status-mux " +
                "collapsed lifecycle/token-usage/busy/terminal-state 4→1",
        )
        assertTrue(
            wsPlusRest <= dataLane,
            "the WS+REST demand ($wsPlusRest) must fit the DATA lane ($dataLane = usable $usable − control $CONTROL_RESERVED_SLOTS) at cap $TUNNEL_POOL_CAP",
        )
        // The load-bearing invariant: NOT zero-headroom. A ≥1-slot margin is the reconnect-overlap room whose absence
        // was the CYP-610 root; reverting WS_RESERVED_SLOTS to the pre-mux 14 makes 14+1 > 13 → this reddens.
        assertTrue(
            dataLane - wsPlusRest >= 1,
            "the DATA lane must keep a reconnect-overlap margin (headroom ${dataLane - wsPlusRest}) — a zero/negative " +
                "budget is the CYP-610 1-up/6-churn edge; cap=16 is coherent ONLY because CYP-846 shrank WS to $WS_RESERVED_SLOTS",
        )
        assertTrue(
            REST_DEDICATED_CONNS <= CONTROL_RESERVED_SLOTS,
            "REST's one shared keep-alive socket ($REST_DEDICATED_CONNS) fits Control's reserve ($CONTROL_RESERVED_SLOTS)",
        )
    }
}
