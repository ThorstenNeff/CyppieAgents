package com.tneff.cyppieagents.net.hub.operator.ui

/**
 * CYP-479 — the seam that verifies an operator's OOB backup/recovery code (CYP-480 §3.2). The **real** backing
 * — code generation (N hashed codes), single-use consumption, verification → device re-enroll — is
 * **server-owned (CYP-459/469)** and **not built yet** (T3: `OperatorDeviceRecovery` is an intentionally-empty
 * Q6-seam; enrollment is first-device-only). This client seam lets the recovery **flow/state** build now
 * against a stub, honestly labelled; the real verifier wires in when the server primitive lands.
 *
 * Fail-closed: no code is ever accepted without the server's say-so (there is no client-side trust anchor for
 * a recovery code). Central-login-alone is **deliberately not a path** (HE/RR7).
 */
fun interface RecoveryCodeVerifier {
    suspend fun verify(code: String): RecoveryVerifyResult
}

/** The server's verdict on a submitted recovery code. */
sealed interface RecoveryVerifyResult {
    /** Valid + unused → the device may re-enroll (= re-pin). */
    data object Accepted : RecoveryVerifyResult
    /** Wrong or already-used code → retryable (try another). */
    data object Invalid : RecoveryVerifyResult
    /** No recovery codes remain → terminal, fail-closed (recover OOB at the hub console). */
    data object Exhausted : RecoveryVerifyResult
}

/**
 * A stub [RecoveryCodeVerifier] for building/testing the flow **until the server primitive (CYP-459/469)** exists.
 * Not a production verifier — it holds no real codes and verifies nothing; it returns a configured [result]
 * (default [RecoveryVerifyResult.Invalid], the fail-closed default: nothing verifies against a store that
 * isn't there yet).
 */
class StubRecoveryCodeVerifier(
    private val result: RecoveryVerifyResult = RecoveryVerifyResult.Invalid,
) : RecoveryCodeVerifier {
    override suspend fun verify(code: String): RecoveryVerifyResult = result
}
