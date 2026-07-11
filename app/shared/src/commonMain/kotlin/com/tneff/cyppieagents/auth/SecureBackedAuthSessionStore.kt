package com.tneff.cyppieagents.auth

/**
 * CYP-413 (Epic CYP-395 S-I) — the production [AuthSessionStore]: the Kratos `X-Session-Token` lives inside the
 * [SecureSessionStore]'s [SessionMaterial]. In Phase 1 the store is in-memory (R7), so this is **behaviour-identical**
 * to [InMemoryAuthSessionStore] — but the token now flows through the secure seam, and [clear] wipes ALL session
 * material (the token plus any future CP ticket), which is exactly what logout / session-end require. Wired as the
 * live repo's session store in `AuthFlip.buildLiveAuthRepository`.
 */
class SecureBackedAuthSessionStore(private val secure: SecureSessionStore) : AuthSessionStore {
    override fun sessionToken(): String? = secure.sessionMaterial()?.kratosSessionToken

    override fun setSessionToken(token: String?) {
        val current = secure.sessionMaterial() ?: SessionMaterial()
        secure.put(current.copy(kratosSessionToken = token), Persistence.SESSION_ONLY)
    }

    /** Override the interface default (`setSessionToken(null)`) to wipe the WHOLE bundle, not just the token field. */
    override fun clear() { secure.clear() }
}
