package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.SecretMasker

/**
 * CYP-410 (S-A) — the **Phase-2 Control-Plane transport seam** (design 15 §2/§5). Today the Local API is the
 * only way into the hub; in Phase 2 a second transport — an OUTBOUND WebSocket tunnel to `api.cyppie-agents.com`,
 * end-to-end encrypted — becomes a second entry that, after authentication, funnels into the **same**
 * [SessionManager] (identical business logic, different transport). This interface is **only created here, not
 * implemented** (per the ratified S-A scope): the live E2E/Noise tunnel is Phase 2.
 *
 * **Egress invariant, anchored now (F4 / BYOA, [MaskingControlPlaneConnector]):** there is exactly ONE outbound
 * path to the Control Plane, and every byte on it passes through [SecretMasker] first. The Control Plane is
 * zero-knowledge toward payload — no agent-chat/code/credential byte may leave in the clear. Phase-2 E2E replaces
 * the plaintext channel; the mask is the belt that survives a Phase-2 bug, and the Anthropic credential (BYOA)
 * therefore never crosses this connector in the clear.
 */
interface ControlPlaneConnector {
    /** Start the outbound CP tunnel (Phase-2 E2E). Phase-1 stub: no-op. */
    suspend fun start() {}

    /** The SINGLE outbound egress to the Control Plane. Implementations MUST route through [SecretMasker]
     *  (use [MaskingControlPlaneConnector]); never send a raw payload. */
    suspend fun egress(payload: String)
}

/** Phase-1 stub: the second transport is not implemented yet. No inbound funnel, no outbound send. */
object NoOpControlPlaneConnector : ControlPlaneConnector {
    override suspend fun egress(payload: String) { /* Phase 2: E2E tunnel to the Control Plane */ }
}

/**
 * CYP-410 (F4/BYOA) — the single **masked** CP egress: masks every payload through [SecretMasker] BEFORE it
 * reaches [delegate], so no credential/payload byte can leave the hub toward the Control Plane in the clear.
 * Wrapping the (Phase-2) real connector in this is what makes "no payload without E2E" a structural property
 * rather than a promise — a Phase-2 egress that forgets to mask cannot bypass it if it is constructed here.
 */
class MaskingControlPlaneConnector(
    private val delegate: ControlPlaneConnector,
    private val mask: (String) -> String = SecretMasker::mask,
) : ControlPlaneConnector {
    override suspend fun start() = delegate.start()
    override suspend fun egress(payload: String) = delegate.egress(mask(payload))
}
