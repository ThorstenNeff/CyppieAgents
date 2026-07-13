package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT

/**
 * CYP-525 GE2 — the per-connect first-enroll backup codes (seam). At `CONNECTED`, [firstEnrollCodes] yields the hub's
 * generated codes **iff** this connect performed a TOFU first-enroll (`grant.firstEnroll == true`, **hub-authoritative
 * — INDEPENDENT of the client's local `isEnrolled`**), else `null` (steady-state / returning device). `null` source
 * ⇒ INERT (no reveal).
 *
 * **No durable persistence** (the ratified server-authoritative model): the codes are secrets (never at-rest) and the
 * ack is within-flow only — the hub's `firstEnroll` drives a re-reveal (with FRESH codes) after a provisional
 * discard, NOT client memory (a stale client ack would wrongly SUPPRESS that needed re-reveal). The real source
 * consumes Backend's `EnrollResponse` frame — that tunnel-frame read-loop is HELD until the authoritative frame spec.
 */
fun interface FirstEnrollCodesSource {
    suspend fun firstEnrollCodes(): List<String>?
}

/**
 * CYP-525 H3 (Reviewer) — the SavedAck must gate on a **validated, complete, non-empty code set**, NEVER a bare
 * button-press: a truncated/empty [EnrollResponse] must never be acknowledged into a finalize against codes the user
 * never had. A valid set is **exactly [BACKUP_CODE_COUNT]** non-blank codes — the count is **single-sourced in
 * `:core`** (hub `mint(BACKUP_CODE_COUNT)` ↔ this client validation, no drift). Fail-closed: any other shape (empty,
 * short, over-count, blank entries) ⇒ `false` ⇒ no ack ⇒ no SavedAck ⇒ the hub never finalizes ⇒ clean re-TOFU.
 */
internal fun isValidCodeSet(codes: List<String>): Boolean =
    codes.size == BACKUP_CODE_COUNT && codes.all { it.isNotBlank() }

/**
 * CYP-525 GE2 (pure, load-bearing) — the CONNECTED gate: at `CONNECTED` with un-acknowledged first-enroll [codes],
 * HOLD at the reveal ([HubConnectUiState.RevealCodes]) — never surface CONNECTED — else pass through. Pure over its
 * inputs so the lockout guard is unit-testable without driving the whole session. [codesAcked] is the **within-flow**
 * (per-connect, non-durable) confirmation, NOT a persisted flag.
 */
internal fun deviceCodesGate(
    hub: HubDescriptor,
    rs: RemoteSessionState,
    codes: List<String>?,
    codesAcked: Boolean,
): HubConnectUiState =
    if (rs.conn == RemoteConnState.CONNECTED && codes != null && !codesAcked) {
        HubConnectUiState.RevealCodes(hub, codes)
    } else {
        HubConnectUiState.RemoteConnecting(hub, rs)
    }
