package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-286 — the response of `POST /api/ws-ticket`: a short-lived, single-use ticket the client passes as
 * `?ticket=` when opening a read WebSocket, instead of a long-lived bearer in the loggable `?token=` query. The
 * raw [ticket] is disclosed ONCE (never logged / re-rendered); it is consumed atomically on first use and expires
 * in [expiresInMs]. No escalation — it resolves to the SAME read subject the (already-authenticated) minter had.
 */
@Serializable
data class WsTicket(
    val ticket: String,
    /** Time-to-live from mint, in milliseconds — short by design (fetch-then-open, not replay-from-log). */
    val expiresInMs: Long,
)
