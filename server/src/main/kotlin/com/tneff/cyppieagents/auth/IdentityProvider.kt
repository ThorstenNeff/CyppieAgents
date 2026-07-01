package com.tneff.cyppieagents.auth

/** A resolved identity from the IdP. [verified] = the identity's email/address is confirmed (Kratos
 *  `verifiable_addresses[].verified`). The guard requires `verified==true` (RC1) — session-valid is not enough. */
data class ResolvedIdentity(val identityId: String, val verified: Boolean)

/**
 * CYP-178 / P1 — the seam over the identity provider (Kratos owns identity; we own authZ). The real impl
 * (`KratosIdentityProvider`) validates a session via `GET /sessions/whoami`; tests use [FakeIdentityProvider].
 *
 * **Contract — MUST fail-closed:** any invalid/expired/absent credential, any IdP error, any timeout →
 * **null** (never a partial/ambiguous identity). The guard treats null as unauthenticated.
 */
interface IdentityProvider {
    suspend fun resolve(sessionCredential: String?): ResolvedIdentity?
}

/** Hermetic fake for the guard tests: a fixed credential→identity map (verified or not); an unknown or
 *  null credential resolves to null (fail-closed) — exactly the real contract. */
class FakeIdentityProvider(private val sessions: Map<String, ResolvedIdentity>) : IdentityProvider {
    override suspend fun resolve(sessionCredential: String?): ResolvedIdentity? =
        sessionCredential?.let { sessions[it] }
}
