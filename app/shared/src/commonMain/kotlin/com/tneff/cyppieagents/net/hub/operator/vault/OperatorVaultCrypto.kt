package com.tneff.cyppieagents.net.hub.operator.vault

/**
 * CYP-542 / B1 — the injectable crypto seams for the [OperatorSecretVault] (the no-hardware App-Passphrase path).
 * Modeled as **interfaces injected at the composition root** (not `expect`/`actual`), mirroring `OperatorDeviceKeyStore`:
 * the jvm root wires the real primitives (Argon2id + JCE AES-GCM), tests inject deterministic fakes, and commonMain
 * stays platform-free. iOS/web actuals are the ② follow-on (with the remote transport ②).
 */

/**
 * The passphrase KDF (freeze ①: **Argon2id `m≥64 MiB, t≥3, p=1`**, single-sourced [Argon2Params.FROZEN]). Derives a
 * 32-byte KEK from the operator passphrase + a per-install random salt. Memory-hard because — on the no-hardware path
 * — the KDF is the **only** barrier against offline brute-force of an exfiltrated vault (§6/H-4). The jvm actual is
 * BouncyCastle `Argon2BytesGenerator`; tests inject a fast deterministic fake.
 */
fun interface PassphraseKdf {
    /** Derive a 32-byte KEK. [passphrase] is a `CharArray` (H-1: never `String`) — the impl must NOT copy it to a String. */
    fun deriveKek(passphrase: CharArray, salt: ByteArray, params: Argon2Params): ByteArray
}

/** Argon2id cost parameters. [FROZEN] is the ratified floor (freeze ①) — never weaken; stronger than login minima. */
data class Argon2Params(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /** Freeze ① (Reviewer crypto-lens): m ≥ 64 MiB, t ≥ 3, p = 1. The single source of the prod cost. */
        val FROZEN: Argon2Params = Argon2Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)
    }
}

/**
 * Authenticated encryption (AES-256-GCM). [open] returns `null` on **any** tag/format failure (wrong KEK == wrong
 * passphrase, OR tamper) — the AEAD tag IS the passphrase verifier (no separate hash). [aad] binds context (H-2) so a
 * sealed blob can't be transplanted across params/installs. The jvm actual is JCE; tests inject a fake.
 */
interface Aead {
    /** Encrypt [plaintext] under [key] (32 B) + [nonce] (12 B), authenticating [aad]. Returns ciphertext‖tag. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    /** Decrypt + verify. `null` on tag mismatch / malformed input (wrong passphrase OR tamper) — never throws to the caller. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray?
    /** Cryptographically-secure random bytes (salt / nonce). jvm = `SecureRandom`. */
    fun randomBytes(n: Int): ByteArray
}

/**
 * The vault blob's at-rest persistence (one opaque byte blob). The jvm actual writes an **owner-only, atomic** file
 * (the CYP-525 `writeOwnerOnly` pattern: `0600`-from-birth temp → atomic move) — the sealed blob is AEAD-encrypted, so
 * this store never sees plaintext key material. [exists] distinguishes first-enroll (missing) from a present vault.
 */
interface VaultStore {
    fun exists(): Boolean
    /** The persisted blob, or `null` if absent/unreadable (a present-but-unreadable file is surfaced as CORRUPT by the vault). */
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}
