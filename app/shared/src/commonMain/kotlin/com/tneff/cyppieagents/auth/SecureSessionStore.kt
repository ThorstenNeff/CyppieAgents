package com.tneff.cyppieagents.auth

/**
 * CYP-413 (Epic CYP-395 S-I) — the session-scoped **secret** bundle the client holds while a session is alive.
 * Phase 1 carries only the Kratos `X-Session-Token` ([kratosSessionToken]); S-K adds the CP-issued, hub-scoped
 * ticket JWT into the SAME type (one store for all session secrets, per the ratified design — not a separate
 * store). **Non-secret** data (e.g. the Control-Plane public verification key) does NOT belong here: it gets a
 * separate, durably-persistable `ControlPlaneKeyCache` in a later slice, so the trust boundary — secret &
 * session-scoped vs. non-secret & durable — stays legible in the type system.
 */
data class SessionMaterial(
    val kratosSessionToken: String? = null,
    // S-K: val hubTicket: String? = null   // the CP-signed, hub-scoped JWT presented in the local handshake
)

/**
 * CYP-413 — how durably a [SecureSessionStore] may keep material. Phase 1 uses ONLY [SESSION_ONLY] (R7: no
 * secret survives the session; every actual is in-memory). [DEVICE_SECURE] is declared for the later
 * hardware-backed "remember me" (Android Keystore / iOS Keychain) but is **unused now** — the enum keeps that
 * choice expressible without committing to it.
 */
enum class Persistence { SESSION_ONLY, DEVICE_SECURE }

/**
 * CYP-413 — the platform seam for session-scoped secret storage (`expect`/`actual`, the [ThemePreferences] idiom).
 *
 * **Phase 1: every actual is in-memory** (R7=session-only) — behaviour-identical to the previous
 * [InMemoryAuthSessionStore]; it backs the existing [AuthSessionStore] via [SecureBackedAuthSessionStore], so the
 * Kratos token now flows through this seam. Durable hardware-backed actuals (Android Keystore, iOS Keychain; Web
 * stays in-memory by construction) are **named, not built** here. Material is [clear]ed on logout AND at session
 * end (the leak guard — a session's secret must not outlive the session).
 */
expect class SecureSessionStore() {
    /** The current session material, or `null` when none is held (empty store). */
    fun sessionMaterial(): SessionMaterial?

    /** Replace the held material. Phase-1 actuals ignore [persistence] (all in-memory); it gates future durability. */
    fun put(material: SessionMaterial, persistence: Persistence)

    /** Wipe ALL held material (token + any future CP ticket). Called on logout and at session end. */
    fun clear()
}
