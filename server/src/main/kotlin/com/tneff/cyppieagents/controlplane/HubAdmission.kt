package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.boot.HubRegistration
import kotlinx.serialization.Serializable

/**
 * CYP-512 (Epic CYP-427 Phase-2, activation) — the hub↔CP **admission** wire contracts (shared by the CP route
 * `HubAdmissionRoutes` and the hub-side [HubAdmissionClient], so neither drifts).
 */

/** The CP-issued single-use PoP challenge nonce (base64); the hub signs its registration transcript over it. */
@Serializable
data class HubChallenge(val nonce: String)

/** The hub's admission request: the signed [HubRegistration] + the CP-issued [nonce] it signed over. */
@Serializable
data class HubAdmissionRequest(val registration: HubRegistration, val nonce: String)

/** The admission outcome (200 + typed body). [reason] is a stable code (never secret): `owner_mismatch` (the claimed
 *  ownerId is not the authenticated operator) · `nonce_invalid` (never-issued / replayed) · `no_operator` · or the
 *  [AdmitResult.Rejected] reason (`pop_invalid` / `hubid_not_self_certifying` / `signing_pub_malformed`). */
@Serializable
data class HubAdmissionResult(val admitted: Boolean, val hubId: String? = null, val reason: String? = null)
