package com.tneff.cyppieagents.auth.operator

/**
 * CYP-485 (③ Recovery-Enforcement, Decision 2 / RR7 / RR2-B) — the concrete replacement for the CYP-469
 * [OperatorDeviceRecovery] seam: enroll a new operator device using an **offline backup code** (a **hub-local**
 * factor), **NEVER central-login alone**. A compromised central login therefore cannot re-enroll a device and seize
 * the hub — the operator must present a hub-issued backup code (or an existing device; the old-key path is separate).
 *
 * This one flow covers both **recovery** (all devices lost → the new device becomes an anchor) and an **OOB
 * additional-enroll** (add a device alongside existing ones): the backup code is the out-of-band authorization in
 * both cases, and [OperatorDeviceStore.add] appends (multi-device) or sets the anchor (single-device). Fail-closed:
 * the device is validated FIRST so a malformed device never burns a single-use code; the code is then consumed
 * **atomically** and only then is the device enrolled.
 */
class OperatorDeviceRecoveryFlow(
    private val store: OperatorDeviceStore,
    private val backupCodes: BackupCodeStore,
) {
    /** Enroll [newDevice] authorized by an offline [backupCode]. Consumes the code single-use on success. */
    fun enrollWithBackupCode(newDevice: EnrolledOperatorDevice, backupCode: String): EnrollResult {
        // Validate the device FIRST — a malformed device must NOT consume a backup code (the operator can retry).
        validate(newDevice)?.let { return EnrollResult.Rejected(it) }
        // ★ The hub-local, offline authorization — never a central login. Atomic single-use.
        if (!backupCodes.consume(backupCode)) return EnrollResult.Rejected("bad_or_used_backup_code")
        store.add(newDevice)
        return EnrollResult.Enrolled(newDevice)
    }

    private fun validate(d: EnrolledOperatorDevice): String? {
        val pkOk = when (d.alg) {
            DeviceKeyAlg.ED25519 -> d.publicKey.size == 32
            DeviceKeyAlg.ES256 -> d.publicKey.size == 65 && d.publicKey[0].toInt() == 0x04
        }
        if (!pkOk) return "bad_public_key"
        if (d.deviceId.isBlank()) return "blank_device_id"
        return null
    }
}
