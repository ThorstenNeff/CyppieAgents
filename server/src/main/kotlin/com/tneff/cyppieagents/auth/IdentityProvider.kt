package com.tneff.cyppieagents.auth

/** A resolved identity from the IdP. [verified] = the identity's email/address is confirmed (Kratos
 *  `verifiable_addresses[].verified`). The guard requires `verified==true` (RC1) — session-valid is not enough. */
data class ResolvedIdentity(val identityId: String, val verified: Boolean)

/**
 * A caller's Kratos session credential **plus its source**, so a whoami/settings call sends the ONE header
 * Kratos accepts for that credential type. **This is load-bearing (real-path bug fix):** Kratos v1.3.0 rejects
 * a request that carries BOTH `X-Session-Token` and the `ory_kratos_session` cookie (native+cookie → 500,
 * browser+token → 401) — the two poison each other. A native token goes ONLY in the header; a browser session
 * ONLY in the cookie.
 */
data class SessionCredential(val value: String, val source: Source) {
    enum class Source {
        /** A native client's `X-Session-Token` header (Kratos session token). */
        HEADER,

        /** A browser's `ory_kratos_session` cookie. */
        COOKIE,
    }
}

/**
 * CYP-178 / P1 — the seam over the identity provider (Kratos owns identity; we own authZ). The real impl
 * (`KratosIdentityProvider`) validates a session via `GET /sessions/whoami`; tests use [FakeIdentityProvider].
 *
 * **Contract — MUST fail-closed:** any invalid/expired/absent credential, any IdP error, any timeout →
 * **null** (never a partial/ambiguous identity). The guard treats null as unauthenticated.
 */
interface IdentityProvider {
    suspend fun resolve(credential: SessionCredential?): ResolvedIdentity?
}

/** Hermetic fake for the guard tests: a fixed credential-VALUE→identity map (verified or not); an unknown or
 *  null credential resolves to null (fail-closed) — exactly the real contract. The [SessionCredential.source]
 *  is irrelevant to the fake (it does no HTTP); the real header-selection is covered by `KratosIdentityProviderTest`. */
class FakeIdentityProvider(private val sessions: Map<String, ResolvedIdentity>) : IdentityProvider {
    override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? =
        credential?.let { sessions[it.value] }
}
