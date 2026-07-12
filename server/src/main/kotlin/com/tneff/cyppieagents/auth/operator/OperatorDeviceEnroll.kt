package com.tneff.cyppieagents.auth.operator

import java.util.concurrent.atomic.AtomicReference

/** The outcome of a device enrollment — fail-closed: anything not [Enrolled] is a reject with a stable code. */
sealed interface EnrollResult {
    data class Enrolled(val device: EnrolledOperatorDevice) : EnrollResult
    data class Rejected(val reason: String) : EnrollResult
}

/**
 * Stores the enrolled operator device(s) — the PoP anchor(s) the [OperatorAssertionVerifier] checks against.
 * CYP-469 was single-device; CYP-485 (③) adds **multi-device** ([devices] / [add]) as **additive defaults** so the
 * existing single-device stores (and their consumers) are unchanged — a multi-device store overrides them.
 */
interface OperatorDeviceStore {
    fun enrolled(): EnrolledOperatorDevice?
    fun save(device: EnrolledOperatorDevice)

    /** CYP-485 — ALL enrolled devices (a PoP may match ANY). Default: the single anchor (backward-compat). */
    fun devices(): List<EnrolledOperatorDevice> = listOfNotNull(enrolled())

    /** CYP-485 — add a device (multi-device enroll / recovery). Default (single-device store): replace the anchor;
     *  a multi-device store overrides to append. */
    fun add(device: EnrolledOperatorDevice) = save(device)
}

/** In-memory store (tests / pre-persistence). Thread-safe single-slot. */
class InMemoryOperatorDeviceStore : OperatorDeviceStore {
    private val ref = AtomicReference<EnrolledOperatorDevice?>(null)
    override fun enrolled(): EnrolledOperatorDevice? = ref.get()
    override fun save(device: EnrolledOperatorDevice) = ref.set(device)
}

/**
 * CYP-485 (③) — an in-memory **multi-device** store: N enrolled devices. [save] sets the single first anchor
 * (clear + add), [add] appends an additional device, [remove] drops one. Thread-safe.
 */
class InMemoryMultiOperatorDeviceStore : OperatorDeviceStore {
    private val list = java.util.concurrent.CopyOnWriteArrayList<EnrolledOperatorDevice>()
    override fun enrolled(): EnrolledOperatorDevice? = list.firstOrNull()
    override fun save(device: EnrolledOperatorDevice) { list.clear(); list.add(device) }
    override fun devices(): List<EnrolledOperatorDevice> = list.toList()
    override fun add(device: EnrolledOperatorDevice) { list.add(device) }
    fun remove(deviceId: String) { list.removeIf { it.deviceId == deviceId } }
    fun count(): Int = list.size
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
