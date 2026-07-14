package com.tneff.cyppieagents.net.hub.operator.vault

/**
 * CYP-542 / B1 — the **capability signal** + **credential policy** the operator-UV impl exposes for the UIUX
 * (frozen contract `cffbf9ff`): ONE capability signal drives BOTH the credential tier (U1) and the biometric offer
 * (U4); the UX only draws the states. The **anchor** (freeze ②): NEVER offer a short brute-forceable PIN where no
 * hardware protects it — a short PIN is allowed ONLY on a hardware-backed install (the enclave rate-limits it).
 */

/** Is a real platform authenticator (biometric / Secure-Enclave) present + usable on this install? ONE signal. */
enum class OperatorAuthCapability { HARDWARE_BACKED, SOFTWARE_ONLY }

/**
 * Probes for a usable platform authenticator (the `Fido2OperatorDeviceKeyStore` path). Injected at the composition
 * root: jvm returns [OperatorAuthCapability.SOFTWARE_ONLY] until the macOS/Windows platform-authenticator lands (the
 * ② progressive enhancement); tests inject either tier. Absence NEVER blocks — it selects the software passphrase path.
 */
fun interface OperatorAuthCapabilityProbe {
    fun capability(): OperatorAuthCapability
}

/**
 * The credential policy + threat-model numbers the UX consumes (never invented UI-side). Derived from the capability:
 * - **SOFTWARE_ONLY (no-hardware):** an **App-Passphrase ≥ [minEntropyBits] bit** (freeze ②, = 64) — [minPinDigits]
 *   is `null` (a short PIN is refused; the KDF is the only offline barrier so entropy must carry it).
 * - **HARDWARE_BACKED:** a short PIN of ≥ [minPinDigits] IS allowed ([minEntropyBits] relaxed) — the platform
 *   authenticator's enclave rate-limits online + the key is non-exportable, so a short PIN isn't offline-forceable.
 * [maxAttempts] + [backoffBaseMs]/[backoffMaxMs] mirror the [OperatorSecretVault] online rate-limit (single-sourced).
 */
data class CredentialPolicy(
    val capability: OperatorAuthCapability,
    val credentialKind: CredentialKind,
    val minEntropyBits: Int,
    val minPinDigits: Int?,
    val offersBiometric: Boolean,
    val maxAttempts: Int,
    val backoffBaseMs: Long,
    val backoffMaxMs: Long,
) {
    companion object {
        /** Freeze ②: the no-hardware entropy floor. A short PIN below this on software is offline-GPU-forceable even under Argon2id. */
        const val SOFTWARE_MIN_ENTROPY_BITS = 64

        /** Hardware-backed short-PIN minimum (enclave-protected → not offline-forceable). */
        const val HARDWARE_MIN_PIN_DIGITS = 6

        fun forCapability(
            capability: OperatorAuthCapability,
            maxAttempts: Int,
            backoffBaseMs: Long,
            backoffMaxMs: Long,
        ): CredentialPolicy = when (capability) {
            OperatorAuthCapability.SOFTWARE_ONLY -> CredentialPolicy(
                capability, CredentialKind.PASSPHRASE,
                minEntropyBits = SOFTWARE_MIN_ENTROPY_BITS, minPinDigits = null, offersBiometric = false,
                maxAttempts = maxAttempts, backoffBaseMs = backoffBaseMs, backoffMaxMs = backoffMaxMs,
            )
            OperatorAuthCapability.HARDWARE_BACKED -> CredentialPolicy(
                capability, CredentialKind.PIN,
                minEntropyBits = 0, minPinDigits = HARDWARE_MIN_PIN_DIGITS, offersBiometric = true,
                maxAttempts = maxAttempts, backoffBaseMs = backoffBaseMs, backoffMaxMs = backoffMaxMs,
            )
        }
    }
}

/** The enroll credential type the UX renders (U1): a passphrase field + strength meter, or a short-PIN field. */
enum class CredentialKind { PASSPHRASE, PIN }
