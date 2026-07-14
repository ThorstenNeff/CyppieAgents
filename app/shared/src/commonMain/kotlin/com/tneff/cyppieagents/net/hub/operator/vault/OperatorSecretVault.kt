package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * CYP-542 / B1 — the **App-Passphrase secret vault**: the CYP-525 Ed25519 device private key stored **AEAD-encrypted
 * under a passphrase-derived KEK** (replaces the plaintext `0600` custody). This is the no-shortcut core: no correct
 * passphrase ⇒ no KEK ⇒ no plaintext key ⇒ **no signature possible** (the UV cryptographically gates signing, not a UI
 * gesture over a plaintext key).
 *
 * Ratified invariants:
 * - **① KDF** = Argon2id [Argon2Params.FROZEN] (m≥64 MiB/t≥3/p=1) — the only offline barrier; ② the passphrase floor
 *   (≥64 bit) is UI-enforced at enroll (the vault stores only ciphertext, never the passphrase).
 * - **③ corrupt ≠ missing (fail-closed):** a MISSING vault ⇒ first-enroll (OK); a CORRUPT/tampered vault ⇒
 *   [VaultState.Corrupt] / [VaultOpen.Corrupt] ⇒ `Unavailable` + OOB-recovery, **NEVER** auto-re-enroll (that would let
 *   an attacker corrupt the vault to force a key they control — the CYP-525-H1b key-substitution vector).
 * - **H-2 AAD:** the seal binds `{version‖salt‖kdf-params‖x509-pub}` so a blob can't be transplanted across installs/params.
 * - **H-4 online-only rate-limit:** the attempt counter bounds **online** (through-the-app) guessing; it resets ONLY on a
 *   correct passphrase, and a lockout escalates. It does NOT (and must not claim to) stop an **offline** attacker who
 *   exfiltrated the file — that threat is bounded solely by ① + ②. The counter is best-effort, not tamper-proof.
 * - **H-1 zeroize:** the KEK is zeroized right after each seal/open; the caller zeroizes the returned private key + the
 *   passphrase `CharArray` after use (see [VaultOpen.Unlocked]).
 */
class OperatorSecretVault(
    private val store: VaultStore,
    private val kdf: PassphraseKdf,
    private val aead: Aead,
    private val nowMs: () -> Long,
    private val params: Argon2Params = Argon2Params.FROZEN,
    private val maxAttempts: Int = 5,
    private val baseLockoutMs: Long = 30_000L,
    private val maxLockoutMs: Long = 15 * 60_000L,
) {

    fun state(): VaultState = when (val blob = readBlob()) {
        BlobRead.Missing -> VaultState.Missing
        BlobRead.Corrupt -> VaultState.Corrupt
        is BlobRead.Present -> VaultState.Enrolled
    }

    /** The enrolled device public key (X.509 SPKI) for hub pinning, or `null` (missing/corrupt). */
    fun devicePublicKey(): ByteArray? = (readBlob() as? BlobRead.Present)?.blob?.x509Pub()

    /**
     * Delete the vault blob (state → [VaultState.Missing]). Internal use only — the **migration fail-safe rollback**
     * (a re-seal that did not verify is discarded so migration retries) and Q5 teardown. NOT a user/attacker path (an
     * attacker-triggered discard-to-force-re-enroll is exactly what ③ forbids; this is never wired to input).
     */
    fun discard() = store.delete()

    /**
     * First-enroll (or change-passphrase re-seal): seal [privKeyPkcs8] under a fresh salt+nonce KEK. Resets the attempt
     * state. The caller MUST have UI-enforced the ② passphrase floor. Zeroizes the KEK. Does NOT hold the private key.
     */
    fun enroll(passphrase: CharArray, privKeyPkcs8: ByteArray, x509Pub: ByteArray) {
        // F-#4 (Reviewer, HIGH) — CORE enforcement, not only UI: the ② floor + #3 blocklist are enforced HERE, at the
        // single seal choke-point through which ALL paths run (first-enroll, change-passphrase, migration). A weak /
        // blocklisted passphrase is REFUSED fail-closed (nothing sealed) — a test / headless / future-orchestrator /
        // bug can never bypass the strength control. The UI gate stays (UX); this is the non-bypassable enforcement.
        require(PassphraseStrength.verdict(passphrase, CredentialPolicy.SOFTWARE_MIN_ENTROPY_BITS) == StrengthVerdict.OK) {
            "operator passphrase is below the enroll floor or is blocklisted — refused (fail-closed core enforcement)"
        }
        val salt = aead.randomBytes(SALT_LEN)
        val nonce = aead.randomBytes(NONCE_LEN)
        val kek = kdf.deriveKek(passphrase, salt, params)
        try {
            val aad = aad(VAULT_VERSION, salt, nonce, params, x509Pub)
            val sealed = aead.seal(kek, nonce, privKeyPkcs8, aad)
            writeBlob(
                VaultBlob(
                    v = VAULT_VERSION,
                    salt = salt.b64(), memKiB = params.memoryKiB, iter = params.iterations, par = params.parallelism,
                    nonce = nonce.b64(), sealed = sealed.b64(), pub = x509Pub.b64(),
                    attempts = 0, lockedUntilMs = 0L,
                ),
            )
        } finally {
            kek.fill(0) // H-1
        }
    }

    /**
     * Verify the passphrase by decrypting the sealed key (the AEAD tag IS the verifier). Returns the plaintext PKCS#8
     * private key on success — **the caller owns zeroizing it** (H-1, held only for the bounded reuse window). Fail-closed
     * at every branch; a corrupt vault is NEVER treated as first-enroll.
     */
    fun open(passphrase: CharArray): VaultOpen {
        val blob = when (val r = readBlob()) {
            BlobRead.Missing -> return VaultOpen.Missing
            BlobRead.Corrupt -> return VaultOpen.Corrupt // ③ fail-closed — never re-enroll
            is BlobRead.Present -> r.blob
        }
        val now = nowMs()
        if (blob.lockedUntilMs > now) return VaultOpen.LockedOut(blob.lockedUntilMs)

        val kek = kdf.deriveKek(passphrase, blob.salt.unb64(), blob.paramsOf())
        val priv = try {
            aead.open(kek, blob.nonce.unb64(), blob.sealed.unb64(), aad(blob.v, blob.salt.unb64(), blob.nonce.unb64(), blob.paramsOf(), blob.x509Pub()))
        } finally {
            kek.fill(0) // H-1
        }

        return if (priv != null) {
            if (blob.attempts != 0 || blob.lockedUntilMs != 0L) writeBlob(blob.copy(attempts = 0, lockedUntilMs = 0L)) // reset ONLY on success
            VaultOpen.Unlocked(priv)
        } else {
            // Wrong passphrase (or tamper the AEAD caught) — bump the ONLINE counter (H-4); escalate a lockout at the cap.
            val attempts = blob.attempts + 1
            val lockedUntil = if (attempts >= maxAttempts) now + backoffMs(attempts) else 0L
            writeBlob(blob.copy(attempts = attempts, lockedUntilMs = lockedUntil))
            if (lockedUntil > now) VaultOpen.LockedOut(lockedUntil) else VaultOpen.WrongPassphrase
        }
    }

    private fun backoffMs(attempts: Int): Long {
        val over = attempts - maxAttempts // 0 at the first lock, then escalating
        val shifted = baseLockoutMs shl over.coerceIn(0, 20)
        return shifted.coerceIn(baseLockoutMs, maxLockoutMs)
    }

    // --- blob (de)serialization: a corrupt/unreadable/unparseable present file ⇒ CORRUPT (never silently re-enrolled) ---

    private sealed interface BlobRead {
        data object Missing : BlobRead
        data object Corrupt : BlobRead
        data class Present(val blob: VaultBlob) : BlobRead
    }

    private fun readBlob(): BlobRead {
        if (!store.exists()) return BlobRead.Missing
        val bytes = store.read() ?: return BlobRead.Corrupt // present-but-unreadable ⇒ corrupt, fail-closed
        val parsed = runCatching { VaultBlob.decode(bytes.decodeToString()) }.getOrNull()
            ?: return BlobRead.Corrupt // unparseable present file ⇒ corrupt (③), never a silent re-enroll
        return if (parsed.isWellFormed()) BlobRead.Present(parsed) else BlobRead.Corrupt
    }

    private fun writeBlob(blob: VaultBlob) = store.write(blob.encode().encodeToByteArray())

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.b64(): String = Base64.encode(this)

    private companion object {
        const val VAULT_VERSION = 1
        const val SALT_LEN = 16
        const val NONCE_LEN = 12

        /** H-2 / F-#2: bind the immutable context into the AEAD AAD so a sealed blob can't be transplanted across
         *  params/installs. The **nonce** is included (5-field: version‖params‖salt‖nonce‖pub) — AES-GCM already binds
         *  the nonce inherently (J0/tag), so this is belt-and-suspenders matching the ratified 5-field wording. */
        fun aad(version: Int, salt: ByteArray, nonce: ByteArray, params: Argon2Params, x509Pub: ByteArray): ByteArray =
            ("v$version|m${params.memoryKiB}|t${params.iterations}|p${params.parallelism}|n").encodeToByteArray() +
                salt + nonce + x509Pub
    }
}

/**
 * The at-rest vault. Encoded as a single **pipe-delimited** line (base64 byte fields have no `|`) — a manual,
 * reflection-free format (`:app:shared` doesn't apply the kotlinx.serialization plugin, and an explicit format is
 * the right posture for a security blob). The `sealed` field is AEAD ciphertext — the plaintext key never lands here.
 * [decode] throws on ANY malformed input (wrong arity / bad number) ⇒ the vault reads that as CORRUPT (③, fail-closed).
 */
data class VaultBlob(
    val v: Int,
    val salt: String,
    val memKiB: Int,
    val iter: Int,
    val par: Int,
    val nonce: String,
    val sealed: String,
    val pub: String,
    val attempts: Int,
    val lockedUntilMs: Long,
) {
    fun encode(): String = listOf(v, salt, memKiB, iter, par, nonce, sealed, pub, attempts, lockedUntilMs).joinToString("|")

    companion object {
        fun decode(line: String): VaultBlob {
            val f = line.trim().split("|")
            require(f.size == 10) { "vault blob arity" }
            return VaultBlob(
                v = f[0].toInt(), salt = f[1], memKiB = f[2].toInt(), iter = f[3].toInt(), par = f[4].toInt(),
                nonce = f[5], sealed = f[6], pub = f[7], attempts = f[8].toInt(), lockedUntilMs = f[9].toLong(),
            )
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun String.unb64(): ByteArray = Base64.decode(this)

@OptIn(ExperimentalEncodingApi::class)
internal fun VaultBlob.x509Pub(): ByteArray = Base64.decode(pub)
internal fun VaultBlob.paramsOf(): Argon2Params = Argon2Params(memoryKiB = memKiB, iterations = iter, parallelism = par)
internal fun VaultBlob.isWellFormed(): Boolean =
    v >= 1 && salt.isNotEmpty() && nonce.isNotEmpty() && sealed.isNotEmpty() && pub.isNotEmpty() &&
        memKiB > 0 && iter > 0 && par > 0 &&
        runCatching { salt.unb64(); nonce.unb64(); sealed.unb64(); pub.unb64() }.isSuccess

/** Vault presence (freeze ③): [Missing] ⇒ first-enroll OK; [Corrupt] ⇒ fail-closed + OOB-recovery (never re-enroll). */
enum class VaultState { Missing, Enrolled, Corrupt }

/** The outcome of [OperatorSecretVault.open]. All non-[Unlocked] are fail-closed (no key material). */
sealed interface VaultOpen {
    /** Correct passphrase — the plaintext PKCS#8 private key; **the caller MUST zeroize it** after the reuse window (H-1). */
    data class Unlocked(val privKeyPkcs8: ByteArray) : VaultOpen
    data object WrongPassphrase : VaultOpen
    data class LockedOut(val untilMs: Long) : VaultOpen
    data object Corrupt : VaultOpen
    data object Missing : VaultOpen
}
