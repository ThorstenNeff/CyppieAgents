package com.tneff.cyppieagents.auth.operator

import java.util.concurrent.atomic.AtomicReference

/** The outcome of a device enrollment — fail-closed: anything not [Enrolled] is a reject with a stable code. */
sealed interface EnrollResult {
    data class Enrolled(val device: EnrolledOperatorDevice) : EnrollResult
    data class Rejected(val reason: String) : EnrollResult
}

/** Stores the enrolled operator device (the PoP anchor). One device in this slice (first-device). */
interface OperatorDeviceStore {
    fun enrolled(): EnrolledOperatorDevice?
    fun save(device: EnrolledOperatorDevice)
}

/** In-memory store (tests / pre-persistence). Thread-safe single-slot. */
class InMemoryOperatorDeviceStore : OperatorDeviceStore {
    private val ref = AtomicReference<EnrolledOperatorDevice?>(null)
    override fun enrolled(): EnrolledOperatorDevice? = ref.get()
    override fun save(device: EnrolledOperatorDevice) = ref.set(device)
}

/**
 * CYP-469 — **First-Device-Enroll**: registers the operator's device public key as the PoP anchor the
 * [OperatorAssertionVerifier] checks against. Fail-closed: enrollment is admitted ONLY when NO device is enrolled
 * yet. A second / replacement / recovery enroll is the **[OperatorDeviceRecovery] SEAM** below — deliberately
 * unimplemented, because re-enroll is NOT central-login-alone (RR7/RR2-B) and waits on the principal's Q6 ratification.
 */
class OperatorDeviceEnrollment(private val store: OperatorDeviceStore) {
    fun enrollFirstDevice(device: EnrolledOperatorDevice): EnrollResult {
        if (store.enrolled() != null) {
            // Never silently overwrite the anchor via a plain enroll — re-enroll is the Q6-gated recovery SEAM.
            return EnrollResult.Rejected("already_enrolled_recovery_is_q6_seam")
        }
        val pkOk = when (device.alg) {
            DeviceKeyAlg.ED25519 -> device.publicKey.size == 32
            DeviceKeyAlg.ES256 -> device.publicKey.size == 65 && device.publicKey[0].toInt() == 0x04
        }
        if (!pkOk) return EnrollResult.Rejected("bad_public_key")
        if (device.deviceId.isBlank()) return EnrollResult.Rejected("blank_device_id")
        store.save(device)
        return EnrollResult.Enrolled(device)
    }
}

/**
 * ⚠️ CYP-469 AC-4 — the **recovery / re-enroll SEAM** (RR7/RR2-B), **Q6-escalated, deliberately NOT implemented**.
 * Replacing a lost operator device must be a **hub-local OOB / backup-code** flow — **never central-login alone**
 * (a compromised central login must not be able to re-enroll a new operator device and seize the hub). The concrete
 * flow waits on the principal's Q6 ratification; this marker documents the boundary so nothing accidentally fills it
 * with a login-only path.
 */
interface OperatorDeviceRecovery {
    // Intentionally empty. Q6-gated: hub-local OOB / backup-codes, never central-login-alone.
}
