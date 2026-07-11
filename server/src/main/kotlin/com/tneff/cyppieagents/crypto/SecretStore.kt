package com.tneff.cyppieagents.crypto

/**
 * CYP-434 (S-B) — the hub's **fail-closed, encrypted-at-rest custody store** for NAMED secrets. It is the
 * foundation the CP chain sits on: S-C stores the hub private keys here (`hub.signingKey` Ed25519, `hub.dhKey`
 * X25519), Phase-2 adds the operator pin / device-key set, and the Anthropic credential's at-rest hardening
 * migrates onto it later. Every value is AEAD-encrypted (CYP-220 [SecretCipher]) under the injected
 * [MasterKeyCustody] master key and bound by injective AAD to its name, so a blob cannot be relocated to another
 * name/store.
 *
 * **Invariants (Reviewer §7 / Doc 16):**
 * 1. **Never logged, never re-rendered** — a plaintext value leaves ONLY via [get], to the caller; there is no
 *    diagnostic/`toString` path that emits it.
 * 2. **Fail-closed** — a wrong/absent master key makes [get] (and store open) **throw** [SecretCipherException];
 *    it NEVER returns a cleartext fallback and NEVER silently swallows a decrypt failure into `null`.
 * 3. **Zero-knowledge toward the Control Plane** — custody content never crosses the CP egress (BYOA); this store
 *    is hub-local only.
 *
 * The interface is String-only (no JVM types) so it is `expect`/`actual`-ready: the JVM hub keeps the
 * [SqliteSecretStore] custody impl; the native client strand can supply its own `actual` later (coordinated
 * cross-strand). The keyset provider is the swappable [MasterKeyCustody] seam, not this contract.
 */
interface SecretStore {
    /** Encrypt-at-rest a named secret, overwriting any existing value under that name. */
    fun put(name: String, secret: String)

    /**
     * The decrypted secret for [name], or `null` if no such secret exists. **Throws** [SecretCipherException] on a
     * wrong master key / tampered ciphertext / AAD mismatch — the caller never receives a partial or plaintext
     * result on failure (fail-closed).
     */
    fun get(name: String): String?

    /** Whether a secret is stored under [name] (does not decrypt it). */
    fun contains(name: String): Boolean

    /** Remove the secret under [name] (no-op if absent). */
    fun delete(name: String)

    /** The names of all stored secrets (never the values). */
    fun names(): Set<String>
}
