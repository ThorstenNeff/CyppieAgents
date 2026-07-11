package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-421 (a) — the response of `GET /api/server-now`: the server's OWN clock at the instant of the call, so a
 * client can stamp its client-BORN transcript rows (the composer echo, a `conn-error` notice) against server
 * time instead of the browser clock (CYP-346). Content-free: a single epoch-ms value.
 *
 * **Replay-immune by construction** — it is the SEND instant, NOT an event's `tsMs` (an event's `tsMs` is only a
 * lower bound on serverNow, so the tempting `skew = lastEventTs − clientNow` estimate backdates a replayed old
 * event by hours). A2 (a REST read at /ws/agent attach) rather than a new WS frame: a browser WebSocket cannot
 * read handshake headers, so it is frame-or-REST, and REST leaves CYP-412's bare `StoredAgentEvent` /ws/agent
 * seam (Dev5's active wiring) untouched at the cost of one attach-time round-trip (ms-scale, negligible vs the
 * minute-scale clock skew it corrects).
 */
@Serializable
data class ServerNow(
    /** The server's wall clock at the moment this response was produced, epoch milliseconds. */
    val serverNowMs: Long,
)
