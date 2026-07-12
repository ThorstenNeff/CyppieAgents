package com.tneff.cyppieagents.net.hub.operator

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * CYP-443 Slice 2 — the **Raw** (software device-key) [OperatorDeviceKeyStore] actual: the cross-platform base
 * (incl. Linux/CI). An **Ed25519** operator device-key signs the channel-bound challenge **behind a mandatory,
 * explicit app-level UV** (the PIN/passphrase prompt via [UserVerification]) → [DevicePoP.Raw]. Fail-closed: UV
 * denied ⇒ [PopResult.UvFailed] (retryable, NOT a hub reject); unavailable ⇒ [PopResult.AuthenticatorUnavailable];
 * no key ⇒ [PopResult.NotEnrolled] — the private key never leaves this store, the CP never holds it (RR2-B).
 *
 * Custody: the [keyPair] is injected (the real at-rest custody = the CYP-413 `SecureSessionStore` `DEVICE_SECURE`
 * pattern / OS keystore — a wiring follow-up); `null` = not enrolled.
 */
class KeystoreOperatorDeviceKeyStore(
    private val userVerification: UserVerification,
    private val keyPair: KeyPair? = null,
) : OperatorDeviceKeyStore {

    override fun isEnrolled(): Boolean = keyPair != null

    override fun devicePublicKey(): ByteArray? = keyPair?.public?.encoded // X.509 SubjectPublicKeyInfo (Ed25519)

    override suspend fun sign(challenge: ByteArray): PopResult {
        val kp = keyPair ?: return PopResult.NotEnrolled
        // UV FIRST — an explicit prompt, never silent; the signature is produced ONLY on real user presence.
        return when (val uv = userVerification.verify(UvReason.OPERATOR_AUTH)) {
            UvOutcome.Verified -> {
                val sig = Signature.getInstance("Ed25519")
                sig.initSign(kp.private)
                sig.update(challenge)
                PopResult.Signed(DevicePoP.Raw(sig.sign()))
            }
            is UvOutcome.Denied -> PopResult.UvFailed(uv.reason)
            UvOutcome.Unavailable -> PopResult.AuthenticatorUnavailable
        }
    }

    companion object {
        /** Enrollment: generate a fresh Ed25519 operator device-key. Custody = injected (CYP-413 store) at wiring time. */
        fun generateDeviceKey(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }
}
