package com.tneff.cyppieagents.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * CYP-441 (S-C) — the hub's **cryptographic identity**: two SEPARATE keys (F2) minted once, then stable forever.
 *  - `signingPubKey` — **Ed25519**, the identity + registration Proof-of-Possession + (Phase-2) JWT-sig context.
 *  - `dhPubKey` — **X25519**, a **reusable Noise-ready static** (raw LE u-coordinate) that Phase-2 Noise_NK
 *    consumes; it is NOT a per-handshake ephemeral. A single Ed25519 key can't serve as the DH static — hence two.
 *
 * Both **public** halves + `hubId` + `createdAt` live in the pub-only [identityFile] (`.cyppie/hub-identity.json`);
 * both **private** halves live encrypted in the S-B [SecretStore] (CYP-434, fail-closed). `hubId` is derived from
 * the signing public key (self-certifying: `hub_` + first 16 hex of SHA-256(signingPubKey)).
 */
@Serializable
data class HubIdentity(
    val hubId: String,
    /** base64 raw 32-byte Ed25519 public (RFC 8032 compressed point). */
    val signingPubKey: String,
    /** base64 raw 32-byte X25519 public (RFC 7748 LE u-coordinate) — the Noise-ready static. */
    val dhPubKey: String,
    val createdAt: Long,
)

/**
 * Provisions/loads the [HubIdentity], **idempotent** and **rotation-safe**. The SecretStore holds the two private
 * keys (`hub.signingKey`, `hub.dhKey`); the pub file holds the [HubIdentity] metadata. `ensure()`:
 *  - **both present + consistent** → load (stable anchor, never re-minted);
 *  - **both absent** → mint fresh, persist;
 *  - **partial** (privates xor pub file) → **fail closed** — refusing to silently rotate/repair the CP anchor
 *    (a rotated key would invisibly poison the Phase-2 Noise/PoP anchor, R5).
 */
class HubIdentityProvisioner(
    private val secrets: SecretStore,
    private val identityFile: Path,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun ensure(): HubIdentity {
        val hasSigning = secrets.contains(SIGNING_KEY)
        val hasDh = secrets.contains(DH_KEY)
        val hasFile = Files.exists(identityFile)

        if (hasSigning && hasDh && hasFile) {
            val identity = readIdentity()
            // Consistency: the stored privates must reproduce the file's public keys — else the anchor is corrupt.
            verifyPublicsMatchPrivates(identity)
            return identity
        }
        if (!hasSigning && !hasDh && !hasFile) {
            return mint()
        }
        // Any partial state is corruption, not a repairable "first boot" — never silently rotate the anchor.
        throw SecretCipherException(
            "hub identity is in a partial state (signing=$hasSigning dh=$hasDh file=$hasFile) — refusing to " +
                "silently rotate the CP anchor; restore the missing half or clear all three to re-provision",
        )
    }

    /** Ed25519 Proof-of-Possession over [message] (a CP-issued nonce) using the stored signing seed. Fail-closed. */
    fun sign(message: ByteArray): ByteArray {
        val seed = secrets.get(SIGNING_KEY)
            ?: throw SecretCipherException("no hub signing key in custody — cannot produce a PoP (fail-closed)")
        return RawKeys.ed25519Sign(Base64.getDecoder().decode(seed), message)
    }

    private fun mint(): HubIdentity {
        val signing = RawKeys.generateEd25519()
        val dh = RawKeys.generateX25519()
        val enc = Base64.getEncoder()
        secrets.put(SIGNING_KEY, enc.encodeToString(signing.privateRaw))
        secrets.put(DH_KEY, enc.encodeToString(dh.privateRaw))
        val identity = HubIdentity(
            hubId = deriveHubId(signing.publicRaw),
            signingPubKey = enc.encodeToString(signing.publicRaw),
            dhPubKey = enc.encodeToString(dh.publicRaw),
            createdAt = now(),
        )
        writeIdentity(identity)
        return identity
    }

    private fun readIdentity(): HubIdentity =
        JSON.decodeFromString(HubIdentity.serializer(), Files.readString(identityFile))

    private fun writeIdentity(identity: HubIdentity) {
        identityFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = identityFile.resolveSibling("${identityFile.fileName}.tmp")
        Files.writeString(tmp, JSON.encodeToString(HubIdentity.serializer(), identity))
        Files.move(
            tmp, identityFile,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /** The stored private seed/scalar must sign/verify against the file's public — proves the pair is intact. */
    private fun verifyPublicsMatchPrivates(identity: HubIdentity) {
        val dec = Base64.getDecoder()
        val signingSeed = secrets.get(SIGNING_KEY)
            ?: throw SecretCipherException("hub signing key vanished from custody")
        // A signature the file's public verifies proves the seed matches signingPubKey (cheap, definitive).
        val probe = "cyppie-hub-identity-probe".encodeToByteArray()
        val sig = RawKeys.ed25519Sign(dec.decode(signingSeed), probe)
        if (!RawKeys.ed25519Verify(dec.decode(identity.signingPubKey), probe, sig)) {
            throw SecretCipherException("hub signing private does not match the stored public — corrupt anchor")
        }
    }

    companion object {
        const val SIGNING_KEY = "hub.signingKey"
        const val DH_KEY = "hub.dhKey"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Self-certifying id: `hub_` + first 16 hex chars of SHA-256(raw signing public key). */
        fun deriveHubId(signingPublicRaw: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(signingPublicRaw)
            val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            return "hub_${hex.take(16)}"
        }
    }
}
