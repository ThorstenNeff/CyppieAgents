package com.tneff.cyppieagents.auth.operator

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-485 (③) — multi-device enroll (N devices, a PoP matches ANY) + recovery-enforcement (backup-code → re-enroll,
 * NEVER central-login-alone). The multi-device verify preserves the CYP-477 nonce grief-guard.
 */
class Cyp485DeviceMgmtTest {

    private val h = ByteArray(32) { (it + 1).toByte() }
    private val hubId = "hub_test"
    private val rpId = "hub.example"
    private fun sign(seed: ByteArray, nonce: ByteArray) = RawKeys.ed25519Sign(seed, operatorAuthChallenge(h, hubId, nonce))
    private fun dev(id: String, pub: ByteArray) = EnrolledOperatorDevice(id, DeviceKeyAlg.ED25519, pub)

    // ---- multi-device store ----

    @Test
    fun multiStore_saveSetsAnchor_addAppends_devicesReturnsAll_remove() {
        val store = InMemoryMultiOperatorDeviceStore()
        store.save(dev("d1", ByteArray(32))) // the first anchor
        store.add(dev("d2", ByteArray(32)))  // an additional device
        assertEquals(2, store.count())
        assertEquals(listOf("d1", "d2"), store.devices().map { it.deviceId })
        assertEquals("d1", store.enrolled()?.deviceId, "enrolled() = the first anchor")
        store.remove("d1")
        assertEquals(listOf("d2"), store.devices().map { it.deviceId }, "remove drops a device")
    }

    @Test
    fun singleStore_devices_additiveDefault_isSingletonOrEmpty() {
        val store = InMemoryOperatorDeviceStore()
        assertTrue(store.devices().isEmpty(), "empty single-device store → no devices")
        store.save(dev("d1", ByteArray(32)))
        assertEquals(listOf("d1"), store.devices().map { it.deviceId }, "the additive default exposes the single anchor")
    }

    // ---- multi-device verify (verifyAny) ----

    @Test
    fun verifyAny_popFromAnyEnrolledDevice_verifies() {
        val v = OperatorAssertionVerifier()
        val a = RawKeys.generateEd25519()
        val b = RawKeys.generateEd25519()
        val devices = listOf(dev("d-a", a.publicRaw), dev("d-b", b.publicRaw))
        val nonce = byteArrayOf(1)
        val r = v.verifyAny(OperatorDevicePoP.Raw(sign(b.privateRaw, nonce)), devices, h, hubId, nonce, rpId)
        assertEquals("d-b", assertIs<AssertionResult.Verified>(r).deviceId, "a PoP from the SECOND enrolled device verifies")
    }

    @Test
    fun verifyAny_nonEnrolledDevice_rejected() {
        val v = OperatorAssertionVerifier()
        val enrolled = RawKeys.generateEd25519()
        val outsider = RawKeys.generateEd25519()
        val nonce = byteArrayOf(2)
        val r = v.verifyAny(OperatorDevicePoP.Raw(sign(outsider.privateRaw, nonce)), listOf(dev("d1", enrolled.publicRaw)), h, hubId, nonce, rpId)
        assertIs<AssertionResult.Rejected>(r)
    }

    @Test
    fun verifyAny_emptyStore_rejected() {
        val r = OperatorAssertionVerifier().verifyAny(OperatorDevicePoP.Raw(byteArrayOf(1, 2, 3)), emptyList(), h, hubId, byteArrayOf(3), rpId)
        assertEquals("no_enrolled_device", assertIs<AssertionResult.Rejected>(r).reason)
    }

    @Test
    fun verifyAny_garbagePoP_doesNotBurnNonce_cyp477Preserved() {
        val v = OperatorAssertionVerifier()
        val d = RawKeys.generateEd25519()
        val devices = listOf(dev("d1", d.publicRaw))
        val nonce = byteArrayOf(4)
        // a garbage PoP (matches no device) is rejected AND must not consume the nonce...
        assertIs<AssertionResult.Rejected>(v.verifyAny(OperatorDevicePoP.Raw(byteArrayOf(9, 9, 9)), devices, h, hubId, nonce, rpId))
        // ...so the SAME nonce still works with a valid PoP (the grief pre-burn is prevented).
        assertIs<AssertionResult.Verified>(v.verifyAny(OperatorDevicePoP.Raw(sign(d.privateRaw, nonce)), devices, h, hubId, nonce, rpId))
    }

    // ---- recovery-enforcement (backup-code → re-enroll, never central-login) ----

    @Test
    fun recovery_validBackupCode_enrollsDevice_consumesCodeSingleUse() {
        val store = InMemoryMultiOperatorDeviceStore()
        val codes = BackupCodeStore()
        val plain = codes.generate()
        val r = OperatorDeviceRecoveryFlow(store, codes).enrollWithBackupCode(dev("recovered", ByteArray(32)), plain.first())
        assertIs<EnrollResult.Enrolled>(r)
        assertEquals("recovered", store.enrolled()?.deviceId, "the device is re-enrolled via the OFFLINE backup code (no central login)")
        assertEquals(9, codes.remaining(), "the backup code is consumed single-use")
    }

    @Test
    fun recovery_wrongOrUsedCode_rejected_deviceNotEnrolled() {
        val store = InMemoryMultiOperatorDeviceStore()
        val codes = BackupCodeStore()
        codes.generate()
        val r = OperatorDeviceRecoveryFlow(store, codes).enrollWithBackupCode(dev("x", ByteArray(32)), "ZZZZZZZZZZZZZZZZ")
        assertEquals("bad_or_used_backup_code", assertIs<EnrollResult.Rejected>(r).reason)
        assertEquals(0, store.count(), "a wrong backup code enrolls nothing")
        assertEquals(10, codes.remaining(), "a wrong code is not consumed")
    }

    @Test
    fun recovery_badDeviceKey_rejected_codeNotBurned_retryable() {
        val store = InMemoryMultiOperatorDeviceStore()
        val codes = BackupCodeStore()
        val plain = codes.generate()
        val flow = OperatorDeviceRecoveryFlow(store, codes)
        // a malformed device (wrong key size) is rejected BEFORE the code is consumed
        assertIs<EnrollResult.Rejected>(flow.enrollWithBackupCode(dev("x", ByteArray(16)), plain.first()))
        assertEquals(10, codes.remaining(), "a malformed device must NOT burn a single-use backup code (retryable)")
        // the same code still works with a valid device
        assertIs<EnrollResult.Enrolled>(flow.enrollWithBackupCode(dev("ok", ByteArray(32)), plain.first()))
    }
}
