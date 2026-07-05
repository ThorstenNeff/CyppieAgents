package com.tneff.cyppieagents.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.KmsEnvelopeAeadKeyManager
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.security.GeneralSecurityException

/**
 * CYP-220 Phase 2a — the **encryption spine** (Design §3): AEAD-encrypt secrets at rest (API keys, remote
 * tokens, and the DSN secrets themselves) so that **the master key (KEK) NEVER lives in a user Postgres**.
 * This module is ONLY the seam + factory + tests — no store is wired to it yet (that lands with the Pg impls).
 *
 * Backed by **Google Tink** (misuse-resistant AEAD; integrity, not just confidentiality). Two operator-selected
 * master-key placements ([MasterKeySource]): a cloud **KMS** KEK, or a **box-local keyset** injected via the
 * environment (`CYPPIE_MASTER_KEY`) — either way the key custody is OUR infra, never a user's DB.
 */

/**
 * The associated-data binding (Design §3.3): a ciphertext is cryptographically bound to its
 * `(storeKey, projectId, field)` context. Tink verifies the AAD on decrypt, so a ciphertext **cannot be
 * relocated** to a different row / store / field — a moved blob fails to decrypt (fail-closed).
 */
data class SecretAad(val storeKey: String, val projectId: String, val field: String) {
    /**
     * The context as AAD bytes — a **length-prefixed, INJECTIVE** encoding (each component = a 4-byte
     * big-endian length + its UTF-8 bytes). A naive `"a|b|c"` delimiter-join is NOT injective: a component
     * containing the delimiter collides two DISTINCT triples onto identical bytes (e.g. `("s","p|x","f")` and
     * `("s|p","x","f")` both → `"s|p|x|f"`), which would let a ciphertext bound to one context decrypt under
     * another — the "cannot-be-relocated" property (Design §3.3) would break. Length-prefixing makes the
     * encoding a bijection with the triple, so no two distinct contexts can ever share AAD bytes.
     */
    fun bytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in listOf(storeKey, projectId, field)) {
            val b = part.encodeToByteArray()
            out.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
            out.write(b)
        }
        return out.toByteArray()
    }
}

/**
 * An encrypted secret + the **key version** that produced it (Design §3.4). BOTH are stored in the PG row
 * (`*_ciphertext bytea`, `key_ver int`) so mixed-version ciphertext decrypts through a rotation.
 */
class EncryptedSecret(val ciphertext: ByteArray, val keyVersion: Int) {
    // ByteArray in a value holder: identity equality only (value equality would be misleading + non-constant-time).
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Thrown on ANY decrypt failure (wrong key / tampered ciphertext / AAD mismatch / unknown version) — fail-closed. */
class SecretCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The encryption seam. [encrypt] stamps the current primary [keyVersion]; [decrypt] verifies the AAD and
 * routes to the version that produced the ciphertext. Every failure throws [SecretCipherException] — a caller
 * never gets a partial/plaintext-on-error result.
 */
interface SecretCipher {
    /** The current PRIMARY key version stamped on new ciphertext. */
    val keyVersion: Int
    fun encrypt(plaintext: String, aad: SecretAad): EncryptedSecret
    fun decrypt(secret: EncryptedSecret, aad: SecretAad): String
}

/**
 * Tink AEAD implementation. Holds one [Aead] per key version ([byVersion]); [keyVersion] (the primary) is used
 * to encrypt, and decrypt routes by the ciphertext's stored version — so a rotation adds a new primary while
 * old-version ciphertext still decrypts (Design §3.4). Within a single version, the Tink keyset may itself hold
 * rotated keys (Tink tries all keys in the set on decrypt), so this is rotation on two orthogonal levels.
 */
class TinkSecretCipher(
    override val keyVersion: Int,
    private val byVersion: Map<Int, Aead>,
) : SecretCipher {
    init { require(byVersion.containsKey(keyVersion)) { "primary key version $keyVersion has no Aead" } }

    private val primary: Aead = byVersion.getValue(keyVersion)

    override fun encrypt(plaintext: String, aad: SecretAad): EncryptedSecret =
        EncryptedSecret(primary.encrypt(plaintext.encodeToByteArray(), aad.bytes()), keyVersion)

    override fun decrypt(secret: EncryptedSecret, aad: SecretAad): String {
        val aead = byVersion[secret.keyVersion]
            ?: throw SecretCipherException("no key material for version ${secret.keyVersion}")
        return try {
            aead.decrypt(secret.ciphertext, aad.bytes()).decodeToString()
        } catch (e: GeneralSecurityException) {
            // Uniform, context-free failure (no oracle): wrong key, a tampered ciphertext, and an AAD mismatch
            // are indistinguishable to the caller — all fail-closed.
            throw SecretCipherException("secret decrypt failed", e)
        }
    }
}

/** Operator-selected master-key placement (Design §3.2) — the KEK custody is OUR infra, never a user PG. */
sealed interface MasterKeySource {
    /**
     * Cloud KMS: the KEK is a `gcp-kms://…` / `aws-kms://…` / vault key; a per-DEK is envelope-wrapped inline,
     * so the user PG holds only `{wrapped_dek, ciphertext, aad}` — useless without our KMS. The provider client
     * (`GcpKmsClient`/`AwsKmsClient`) must be registered via `KmsClients.add(...)` at WIRING time (later phase);
     * that keeps the heavy cloud-SDK deps out of this spine.
     */
    data class Kms(val kekUri: String) : MasterKeySource

    /**
     * Box-local keyset: a Tink AEAD keyset serialized into the environment (`CYPPIE_MASTER_KEY`, out-of-repo,
     * box-scoped, injected like `ANTHROPIC_API_KEY`). Lives on OUR box only — never in a user PG.
     */
    data class Box(val serializedKeyset: String) : MasterKeySource
}

/** Which master-key mode a deployment uses (operator-selected in config; wiring lands in a later phase). */
enum class MasterKeyMode { KMS, BOX }

/** The (future) boot config shape for the spine — mode + KEK uri + the primary key version. Not wired yet. */
data class EncryptionConfig(
    val mode: MasterKeyMode,
    val kekUri: String? = null,          // required for KMS
    val primaryKeyVersion: Int = 1,
)

/** Builds a [SecretCipher] from a master-key source. `AeadConfig.register()` runs once on first use. */
object SecretCipherFactory {
    init { AeadConfig.register() }

    /** Build the [Aead] for one key version from its [MasterKeySource]. */
    fun aead(source: MasterKeySource): Aead = when (source) {
        is MasterKeySource.Box -> {
            val handle = TinkJsonProtoKeysetFormat.parseKeyset(source.serializedKeyset, InsecureSecretKeyAccess.get())
            @Suppress("DEPRECATION") handle.getPrimitive(Aead::class.java)
        }
        is MasterKeySource.Kms -> {
            // The KMS-envelope keyset only REFERENCES the remote KEK (no local secret to persist). A registered
            // KmsClient resolves the kekUri at getPrimitive() time (wired later).
            val template = KmsEnvelopeAeadKeyManager.createKeyTemplate(source.kekUri, KeyTemplates.get("AES256_GCM"))
            @Suppress("DEPRECATION") KeysetHandle.generateNew(template).getPrimitive(Aead::class.java)
        }
    }

    /** A single-version cipher. */
    fun single(version: Int, source: MasterKeySource): SecretCipher =
        TinkSecretCipher(version, mapOf(version to aead(source)))

    /** A rotation-ready cipher: [primaryVersion] encrypts; each mapped version can decrypt its own ciphertext. */
    fun rotating(primaryVersion: Int, sources: Map<Int, MasterKeySource>): SecretCipher =
        TinkSecretCipher(primaryVersion, sources.mapValues { aead(it.value) })

    /**
     * Resolve a cipher from [EncryptionConfig] + the injected `CYPPIE_MASTER_KEY` value (BOX). Fail-closed: a
     * BOX mode without a master key, or a KMS mode without a kekUri, throws (no silent plaintext fallback).
     */
    fun fromConfig(config: EncryptionConfig, masterKey: String?): SecretCipher = when (config.mode) {
        MasterKeyMode.BOX -> {
            val key = masterKey?.takeIf { it.isNotBlank() }
                ?: throw SecretCipherException("BOX encryption mode requires CYPPIE_MASTER_KEY (none provided)")
            single(config.primaryKeyVersion, MasterKeySource.Box(key))
        }
        MasterKeyMode.KMS -> {
            val uri = config.kekUri?.takeIf { it.isNotBlank() }
                ?: throw SecretCipherException("KMS encryption mode requires a kekUri (none provided)")
            single(config.primaryKeyVersion, MasterKeySource.Kms(uri))
        }
    }

    /**
     * Provision a fresh BOX keyset (serialized JSON) — for generating `CYPPIE_MASTER_KEY` at deploy setup (and
     * for tests). AES-256-GCM. The output IS the secret; treat it like any master key (box-only, never a user PG).
     */
    fun newBoxKeyset(): String {
        val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        return TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
    }
}
