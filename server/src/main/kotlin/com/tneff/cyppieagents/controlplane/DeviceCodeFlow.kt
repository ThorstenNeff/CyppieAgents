package com.tneff.cyppieagents.controlplane

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

sealed interface DeviceCodeState {
    object Pending : DeviceCodeState
    data class Approved(val operatorId: String) : DeviceCodeState
    object Denied : DeviceCodeState
    object Expired : DeviceCodeState
    object Unknown : DeviceCodeState
}

/** A device-code grant. `deviceCode` (the polling secret) + `userCode` (the human code) are SECRET-shaped (F7,
 *  CYP-190 class) — never logged/rendered; [toString] redacts them. */
class DeviceCodeGrant(val deviceCode: String, val userCode: String, val expiresAtMs: Long) {
    override fun toString(): String = "DeviceCodeGrant(expiresAtMs=$expiresAtMs, codes=<redacted>)"
}

/**
 * CYP-451 (S-D / R3, R6) — a minimal, **INERT** device-code operator-auth **seam** for the headless hub. The
 * operator approves a `userCode` out-of-band in a Kratos-authenticated browser; the LIVE Kratos/OIDC HTTP
 * integration is Phase-2/deploy, so this models the state machine + the **operator PIN** so the approved session
 * yields EXACTLY the operator pinned at provisioning — which becomes the CpJwt `sub` (S-E's `sub==pin` closes the
 * loop). `deviceCode`/`userCode` are secret-shaped (F7 redaction).
 *
 * **Fail-closed pin:** [approve] admits ONLY the provisioned [pinnedOperatorId]; any other identity → `Denied`
 * (never a token for the wrong operator). Nothing here is deployed until a Phase-2 remote-GO.
 */
class DeviceCodeFlow(
    private val pinnedOperatorId: String,
    /** Injectable (device_code, user_code) generator; default = CSPRNG. */
    private val codes: () -> Pair<String, String> = ::randomCodes,
) {
    private class Entry(@Volatile var state: DeviceCodeState, val expiresAtMs: Long)

    private val byDeviceCode = ConcurrentHashMap<String, Entry>()
    private val byUserCode = ConcurrentHashMap<String, String>() // userCode → deviceCode

    fun issue(nowMs: Long, ttlMs: Long): DeviceCodeGrant {
        val (dc, uc) = codes()
        val exp = nowMs + ttlMs
        byDeviceCode[dc] = Entry(DeviceCodeState.Pending, exp)
        byUserCode[uc] = dc
        return DeviceCodeGrant(dc, uc, exp)
    }

    /** Out-of-band, Kratos-authenticated approval. Operator PIN (fail-closed): only [pinnedOperatorId] is admitted. */
    fun approve(userCode: String, operatorId: String, nowMs: Long): Boolean {
        val dc = byUserCode[userCode] ?: return false
        val e = byDeviceCode[dc] ?: return false
        if (nowMs > e.expiresAtMs) { e.state = DeviceCodeState.Expired; return false }
        if (operatorId != pinnedOperatorId) { e.state = DeviceCodeState.Denied; return false } // PIN — never the wrong operator
        e.state = DeviceCodeState.Approved(operatorId)
        return true
    }

    fun poll(deviceCode: String, nowMs: Long): DeviceCodeState {
        val e = byDeviceCode[deviceCode] ?: return DeviceCodeState.Unknown
        if (e.state === DeviceCodeState.Pending && nowMs > e.expiresAtMs) e.state = DeviceCodeState.Expired
        return e.state
    }
}

private val DEVICE_CODE_RNG = SecureRandom()
private val DEVICE_CODE_B64 = Base64.getUrlEncoder().withoutPadding()

/** device_code = 32 CSPRNG bytes (the polling secret); user_code = 8 uppercase chars (human). Both secret-shaped. */
private fun randomCodes(): Pair<String, String> {
    val dc = ByteArray(32).also(DEVICE_CODE_RNG::nextBytes)
    val uc = ByteArray(6).also(DEVICE_CODE_RNG::nextBytes)
    return DEVICE_CODE_B64.encodeToString(dc) to DEVICE_CODE_B64.encodeToString(uc).take(8).uppercase()
}
