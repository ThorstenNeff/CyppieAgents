package com.tneff.cyppieagents.net.hub.operator.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CYP-542 / B1 — the jvm [Aead] actual: **AES-256-GCM** via JCE. [open] maps a tag/format failure (JCE throws
 * `AEADBadTagException`) to `null` — the caller ([OperatorSecretVault]) reads that as wrong-passphrase-OR-tamper and
 * fails closed (never a bypass, never a throw leaking to the connect flow). 128-bit tag, caller-supplied 12-byte nonce.
 */
class JceAead : Aead {
    private val rng = SecureRandom()

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext) // ciphertext ‖ 16-byte tag
    }

    override fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed)
        }.getOrNull() // AEADBadTagException / IllegalBlockSize / short input ⇒ null (fail-closed, no throw to caller)

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
