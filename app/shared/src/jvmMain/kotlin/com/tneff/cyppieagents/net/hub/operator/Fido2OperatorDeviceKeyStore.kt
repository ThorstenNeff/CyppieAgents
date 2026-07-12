package com.tneff.cyppieagents.net.hub.operator

/**
 * CYP-443 Slice 2 — the **Fido2** (platform-authenticator) [OperatorDeviceKeyStore] SEAM: progressive enhancement
 * where the OS has a platform authenticator (macOS Touch-ID / Windows Hello).
 *
 * The real impl binds **libfido2 via JDK FFM** (spike-verified surface): `fido_assert_set_clientdata_hash` =
 * `SHA-256(challenge)`, `fido_assert_set_uv(FIDO_OPT_TRUE)`, then `fido_dev_get_assert` → `{fido_assert_id,
 * authdata, sig}` → [DevicePoP.Fido2]; verifier checks raw-CTAP style. **This is runtime-verified on macOS/Windows
 * later** — flagged at branch-reif.
 *
 * **On this platform (Linux CI) there is NO platform authenticator** → this reports [PopResult.AuthenticatorUnavailable]
 * (honest fail-closed, never a bypass), so the OS-selection at the composition root falls back cleanly to
 * [KeystoreOperatorDeviceKeyStore] (the Raw path).
 */
class Fido2OperatorDeviceKeyStore(
    private val platformAuthenticatorAvailable: Boolean = false,
) : OperatorDeviceKeyStore {

    override fun isEnrolled(): Boolean = false // CTAP enrollment is the macOS/Windows follow-up; not reachable on Linux

    override fun devicePublicKey(): ByteArray? = null

    override suspend fun sign(challenge: ByteArray): PopResult {
        // TODO(mac/win, follow-up): libfido2/FFM — SHA-256(challenge) → clientDataHash, get_assert w/ UV required,
        //   → DevicePoP.Fido2(credentialId, authenticatorData, signature). Until then, honestly unavailable here.
        return PopResult.AuthenticatorUnavailable
    }
}
