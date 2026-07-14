package com.tneff.cyppieagents.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CYP-434 (S-B / F5) — the **injected master-key custody policy**. It provides the serialized Tink master keyset
 * that backs a [SecretStore]'s [SecretCipher]. WHERE the master key comes from is the operator's decision (F5
 * ratification), never hardcoded: the Phase-1 defaults (R4) are an **env-injected keyset** ([EnvKeysetMasterKeyCustody])
 * or an **operator passphrase** ([PassphraseMasterKeyCustody]); KMS / TPM / a native OS keystore (Phase-2 RR6-ii)
 * are **additive** impls behind this SAME seam — the [SecretStore] never changes when custody does.
 *
 * **Fail-closed invariant (Reviewer §7):** a missing / unreadable custody source **throws** ([SecretCipherException]) —
 * the hub refuses to start. It NEVER degrades to a silent plaintext-on-disk fallback (exactly the `local.properties`
 * / CYP-190 class of leak that must not be inherited). There is no "for free" custody on foreign hardware.
 */
fun interface MasterKeyCustody {
    /**
     * The serialized Tink AEAD keyset (the master key) that a [SecretStore] turns into its [SecretCipher].
     * Throws [SecretCipherException] when the custody source is absent or cannot be unlocked (fail-closed).
     */
    fun masterKeyset(): String
}

/**
 * **MVP default (R4).** The master keyset is injected out-of-repo through the env var `CYPPIE_MASTER_KEY` — a
 * serialized Tink AEAD keyset, provisioned once with [SecretCipherFactory.newBoxKeyset] and held box-scoped like
 * `ANTHROPIC_API_KEY` (never in the repo, never logged). Missing/blank → fail-closed throw (no plaintext fallback).
 */
class EnvKeysetMasterKeyCustody(
    private val env: (String) -> String? = System::getenv,
) : MasterKeyCustody {
    override fun masterKeyset(): String =
        env(ENV_VAR)?.takeIf { it.isNotBlank() }
            ?: throw SecretCipherException(
                "$ENV_VAR is required for master-key custody (none provided) — refusing to start; no plaintext fallback",
            )

    companion object {
        const val ENV_VAR = "CYPPIE_MASTER_KEY"
    }
}

/**
 * **Phase-1 R4 option.** The master keyset is a random Tink keyset **wrapped at rest under an operator passphrase**:
 * first use provisions a fresh keyset and seals it (PBKDF2WithHmacSHA256 → AES-256-GCM KEK, random salt+nonce) into
 * [wrappedPath] (owner-only `0600`); later boots re-derive the KEK from the **same passphrase + stored salt** and
 * unwrap it. This is textbook password-based encryption (a KEK protecting the keyset), NOT novel crypto — the
 * data-plane AEAD stays the reused Tink [SecretCipher].
 *
 * **Fail-closed:** a wrong passphrase fails the GCM authentication tag → [SecretCipherException] (no plaintext
 * fallback, no oracle). The passphrase is taken as a caller-owned `CharArray`; the derived KEK bytes are zeroed
 * after each seal/open (the PBKDF2 spec's own copy is cleared too).
 */
class PassphraseMasterKeyCustody(
    private val passphrase: CharArray,
    private val wrappedPath: Path,
    private val newKeyset: () -> String = SecretCipherFactory::newBoxKeyset,
) : MasterKeyCustody {

    override fun masterKeyset(): String {
        wrappedPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val sealed = if (Files.exists(wrappedPath)) {
            SealedKeyset.parse(Files.readString(wrappedPath))
        } else {
            // Provision once: a fresh random master keyset, sealed under this passphrase.
            val fresh = newKeyset()
            val provisioned = Pbe.seal(passphrase, fresh)
            writeOwnerOnly(wrappedPath, provisioned.serialize())
            return fresh
        }
        return Pbe.open(passphrase, sealed) // wrong passphrase → GCM tag failure → SecretCipherException (fail-closed)
    }

    private fun writeOwnerOnly(path: Path, content: String) {
        // tmp-then-atomic-move + 0600, parity with the other secret-at-rest stores (never a world-readable window).
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, content)
        runCatching {
            Files.setPosixFilePermissions(
                tmp,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

/** The at-rest wrapped-keyset envelope: salt + nonce + ciphertext + KDF iteration count (all public but useless
 *  without the passphrase). Serialized as a compact `v1:iters:salt:nonce:ct` (Base64 parts) line. */
internal class SealedKeyset(
    val salt: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val iterations: Int,
) {
    fun serialize(): String {
        val b64 = Base64.getEncoder()
        return "v1:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(nonce)}:${b64.encodeToString(ciphertext)}"
    }

    companion object {
        fun parse(s: String): SealedKeyset {
            val parts = s.trim().split(":")
            if (parts.size != 5 || parts[0] != "v1") {
                throw SecretCipherException("wrapped master keyset is malformed (refusing to start)")
            }
            val dec = Base64.getDecoder()
            val iters = parts[1].toIntOrNull()
                ?: throw SecretCipherException("wrapped master keyset has an invalid KDF cost")
            return SealedKeyset(dec.decode(parts[2]), dec.decode(parts[3]), dec.decode(parts[4]), iters)
        }
    }
}

/**
 * Password-based encryption for the master keyset (JDK-only, standard construction): PBKDF2WithHmacSHA256 derives a
 * 256-bit KEK from the passphrase + a random 16-byte salt; AES-256-GCM (96-bit random nonce, 128-bit tag) seals the
 * serialized Tink keyset. Decryption verifies the tag — a wrong passphrase is indistinguishable from a tampered
 * blob (both fail-closed, no oracle).
 *
 * **KDF choice — PBKDF2-600k, not Argon2id (CYP-548, deliberate):** this custody protects a HIGH-entropy operator
 * passphrase in a `0600` file against OFFLINE brute-force (single-boot-unlock) — a different threat model from Kratos's
 * Argon2id (LOW-entropy end-user passwords, ONLINE no-lockout N-guess where the per-attempt cost floor is the whole
 * defense). It is also a non-default option (env-keyset custody is the default) and JDK-only (Argon2id needs a native
 * lib). 600k iters is OWASP-current. The [SealedKeyset] format is versioned (`v1:iters:…`), so an Argon2id `v2:`
 * migration stays free if a trigger fires. Full rationale + triggers: `docs/design/CYP-548-master-key-kdf-rationale.md`.
 */
private object Pbe {
    private const val ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    fun seal(passphrase: CharArray, plaintextKeyset: String): SealedKeyset {
        val salt = ByteArray(SALT_BYTES).also(rng::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(rng::nextBytes)
        val kek = derive(passphrase, salt, ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, nonce))
            val ct = cipher.doFinal(plaintextKeyset.encodeToByteArray())
            return SealedKeyset(salt, nonce, ct, ITERATIONS)
        } finally {
            zero(kek.encoded)
        }
    }

    fun open(passphrase: CharArray, sealed: SealedKeyset): String {
        val kek = derive(passphrase, sealed.salt, sealed.iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, sealed.nonce))
            cipher.doFinal(sealed.ciphertext).decodeToString()
        } catch (e: java.security.GeneralSecurityException) {
            // Wrong passphrase / tampered blob → uniform fail-closed (no oracle).
            throw SecretCipherException("master keyset could not be unlocked (wrong passphrase or tampered)", e)
        } finally {
            zero(kek.encoded)
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun zero(bytes: ByteArray?) {
        if (bytes != null) java.util.Arrays.fill(bytes, 0)
    }
}
