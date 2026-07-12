package com.tneff.cyppieagents.controlplane

import java.util.Base64

/**
 * CYP-508 (Epic CYP-427 Phase-2, activation) — the **§3 wiring swap**: build the LIVE [LiveHubTicketMinter] ONLY
 * behind the Phase-2-Remote-GO gate (`CYPPIE_REMOTE_RELAY_URL`) AND with the CP signing config present; **fail-closed
 * to [InertHubTicketMinter] on ANY gap** — exact parity with `buildRemoteTransport`. INERT by default → the current
 * server never mints. [env] is injectable so the gate is unit-testable without real env.
 *
 * ★ **CP signing-seed custody (PO go-A, Reviewer-flagged hardening):** the CP mints with an env-provisioned Ed25519
 * seed `CYPPIE_CP_SIGNING_SEED` (base64, 32-byte) + `CYPPIE_CP_KID` / `CYPPIE_CP_ISSUER` — the SAME custody model as
 * `CYPPIE_MASTER_KEY` and the `CYPPIE_CP_*` hub-side pins (the hub verifies with the matching public key). A
 * **SecretStore-at-rest** custody (encrypted, generated-on-boot) is the tracked hardening the **Reviewer weighs at the
 * CYP-508 gate** (expected: MVP-A + a follow-up ticket) — flagged here, not silently dropped; the seed also goes into
 * the PO's flip-authorization package so the Auftraggeber sees the posture. A compromised seed forges operator
 * identity but NEVER the device PoP (RR2-B), so the seizure floor holds under either custody.
 */
fun buildHubTicketMinter(
    registrar: HubRegistrar,
    /** Resolve the [CpOperatorSession] (carrying the route-authenticated operator) → the operator id; `null` = no
     *  operator (→ CP_SESSION_EXPIRED). The route is operator-gated, so this is the identity of the gate's principal —
     *  never anything from the request body (POINT 2: `sub` is never client-chosen). */
    operatorAuthenticate: (CpOperatorSession) -> String?,
    nowMs: () -> Long = System::currentTimeMillis,
    env: (String) -> String? = System::getenv,
): HubTicketMinter {
    env("CYPPIE_REMOTE_RELAY_URL")?.takeIf { it.isNotBlank() } ?: return InertHubTicketMinter
    val seed = env("CYPPIE_CP_SIGNING_SEED")
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        ?.takeIf { it.size == 32 } // Ed25519 seed
        ?: return InertHubTicketMinter
    val kid = env("CYPPIE_CP_KID")?.takeIf { it.isNotBlank() } ?: return InertHubTicketMinter
    val issuer = env("CYPPIE_CP_ISSUER")?.takeIf { it.isNotBlank() } ?: return InertHubTicketMinter
    return LiveHubTicketMinter(
        authenticate = operatorAuthenticate,
        registrar = registrar,
        cpJwtMinter = CpJwtMinter(seed, kid, issuer),
        nowMs = nowMs,
    )
}
