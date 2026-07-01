package com.tneff.cyppieagents.auth

/**
 * Holds the native Kratos **session token** the client replays as `X-Session-Token` (CYP-182). Browser
 * targets (Web/Wasm) don't use this — the `ory_kratos_session` cookie rides along automatically on the
 * same-origin proxy — so on those targets the store simply stays empty and the header is never added.
 *
 * Durable cross-restart persistence (Keychain/DataStore/localStorage) is a **platform `actual`** concern
 * (auth-spec §0: "Session-Persistenz … Plattform-`actual`-Detail") and is deferred; the in-memory default
 * ([InMemoryAuthSessionStore]) is correct for a running session and for the hermetic tests.
 */
interface AuthSessionStore {
    fun sessionToken(): String?
    fun setSessionToken(token: String?)
    fun clear() = setSessionToken(null)
}

/** Process-local session-token holder. The default until a platform persists it (in-memory, MVP). */
class InMemoryAuthSessionStore(initial: String? = null) : AuthSessionStore {
    private var token: String? = initial
    override fun sessionToken(): String? = token
    override fun setSessionToken(token: String?) { this.token = token }
}
