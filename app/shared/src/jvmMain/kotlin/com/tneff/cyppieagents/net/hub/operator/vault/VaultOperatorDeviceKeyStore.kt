package com.tneff.cyppieagents.net.hub.operator.vault

import com.tneff.cyppieagents.net.hub.operator.DevicePoP
import com.tneff.cyppieagents.net.hub.operator.OperatorDeviceKeyStore
import com.tneff.cyppieagents.net.hub.operator.PopResult
import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.UvReason
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * CYP-542 / B1 (§4.4) — the vault-backed [OperatorDeviceKeyStore]: the Ed25519 device key is NOT pre-loaded plaintext
 * (the CYP-525 custody bug this fixes). [sign] runs the UV ([userVerification] = the shared caching UV wrapping the
 * passphrase prompt), which on success populates the [keyHold]; the store then signs from the (bounded, zeroized)
 * held key. **No correct passphrase ⇒ no held key ⇒ no signature** (the no-shortcut invariant, at the signing edge).
 *
 * ③ vault-state routing: **Missing** ⇒ [PopResult.NotEnrolled] (first-enroll). **Corrupt** ⇒
 * [PopResult.AuthenticatorUnavailable] with **no prompt** (fail-closed → OOB-recovery, NEVER re-enroll — the
 * key-substitution guard). [isEnrolled] is `true` for a Corrupt vault too (a vault EXISTS) so a corrupt vault does not
 * route to the enroll step. **Enrolled** ⇒ the UV path.
 */
class VaultOperatorDeviceKeyStore(
    private val vault: OperatorSecretVault,
    private val userVerification: UserVerification,
    private val keyHold: DecryptedKeyHold,
) : OperatorDeviceKeyStore {

    override fun isEnrolled(): Boolean = vault.state() != VaultState.Missing

    override fun devicePublicKey(): ByteArray? = vault.devicePublicKey() // X.509 SPKI (as KeystoreOperatorDeviceKeyStore)

    override suspend fun sign(challenge: ByteArray): PopResult = when (vault.state()) {
        VaultState.Missing -> PopResult.NotEnrolled
        VaultState.Corrupt -> PopResult.AuthenticatorUnavailable // ③ fail-closed, no prompt, → OOB-recovery
        VaultState.Enrolled -> when (val uv = userVerification.verify(UvReason.OPERATOR_AUTH)) {
            UvOutcome.Verified -> {
                val key = keyHold.get() ?: return PopResult.AuthenticatorUnavailable // the reuse window raced/expired
                try {
                    PopResult.Signed(DevicePoP.Raw(ed25519Sign(key, challenge)))
                } finally {
                    key.fill(0) // zeroize the defensive copy (the hold's original zeroizes on window-expiry)
                }
            }
            is UvOutcome.Denied -> PopResult.UvFailed(uv.reason) // wrong passphrase / cancelled / locked — retryable, local
            UvOutcome.Unavailable -> PopResult.AuthenticatorUnavailable // corrupt/missing (raced) — fail-closed
        }
    }

    private fun ed25519Sign(pkcs8: ByteArray, challenge: ByteArray): ByteArray {
        val priv = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) // JDK SunEC (not BC)
        return Signature.getInstance("Ed25519").apply { initSign(priv); update(challenge) }.sign()
    }
}
