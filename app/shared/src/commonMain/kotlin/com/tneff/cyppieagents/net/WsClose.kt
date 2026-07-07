package com.tneff.cyppieagents.net

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * CYP-291 — shared WS close handling so every `/ws/` route's live client treats a **1008 (VIOLATED_POLICY)** revoke
 * consistently as TERMINAL. Without this, a revoked/invalid token that the server closes with 1008 masquerades
 * as a plain transient `Disconnected`; under `.reconnecting()` the client then re-opens the socket with the
 * revoked token every backoff period forever (the CYP-289 reconnect-loop class). Each client reads the code with
 * [readCloseCode] and maps [isAccessRevoked] to its OWN terminal live-event, on which the VM cancels the collect
 * (no reconnect). Centralising the 1008 constant here means a new client can't silently re-introduce the loop.
 *
 * [readCloseCode] must run AFTER the frame loop and under [NonCancellable] — Ktor tears a closing socket down by
 * cancelling the frame channel, so a 1008 would otherwise be lost to that cancellation.
 */
suspend fun DefaultClientWebSocketSession.readCloseCode(): Short? =
    withContext(NonCancellable) { closeReason.await()?.code }

/** True iff the server closed the socket with 1008 VIOLATED_POLICY — a revoked/invalid token (terminal). */
fun isAccessRevoked(closeCode: Short?): Boolean = closeCode == CloseReason.Codes.VIOLATED_POLICY.code
