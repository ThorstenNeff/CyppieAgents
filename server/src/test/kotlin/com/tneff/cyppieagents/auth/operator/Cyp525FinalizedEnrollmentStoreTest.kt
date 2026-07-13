package com.tneff.cyppieagents.auth.operator

import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipherException
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-525 (H1/H1b) — [FinalizedEnrollmentStore] commits the device anchor AND the code-hashes as ONE encrypted,
 * crash-atomic record: it survives a restart, a crash before the rename leaves the OLD record (all-or-nothing), an
 * absent record is `null` (safe re-mint), and a TAMPERED record throws fail-closed (never empty→re-enroll).
 */
class Cyp525FinalizedEnrollmentStoreTest {

    private fun cipher() = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))
    private fun tmp(): File = File(Files.createTempDirectory("cyp525-final").toFile(), "enrollment.rec")
    private fun record(deviceId: String = "op-1") = FinalizedEnrollment(
        device = EnrolledOperatorDevice(deviceId, DeviceKeyAlg.ED25519, ByteArray(32) { it.toByte() }),
        codes = listOf(BackupCodeEntry(ByteArray(16) { 1 }, ByteArray(32) { 2 }, consumed = false),
            BackupCodeEntry(ByteArray(16) { 3 }, ByteArray(32) { 4 }, consumed = true)),
    )

    @Test
    fun commit_read_roundTrip_deviceAndCodesTogether() {
        val file = tmp(); val c = cipher()
        FinalizedEnrollmentStore(c, file, "hub-1").commit(record())
        val got = FinalizedEnrollmentStore(c, file, "hub-1").read()!!
        assertEquals("op-1", got.device.deviceId)
        assertTrue(got.device.publicKey.contentEquals(ByteArray(32) { it.toByte() }), "the device anchor round-trips")
        assertEquals(2, got.codes.size, "the code-hashes round-trip WITH the anchor (one record)")
        assertEquals(true, got.codes[1].consumed, "the consumed flag round-trips")
    }

    @Test
    fun absentRecord_isNull_safeReMint() {
        assertNull(FinalizedEnrollmentStore(cipher(), tmp(), "hub-1").read(), "an absent record → null (a pre-finalize crash → safe re-mint)")
    }

    @Test
    fun crashBeforeRename_leavesOldRecord_allOrNothing() {
        val file = tmp(); val c = cipher()
        val store = FinalizedEnrollmentStore(c, file, "hub-1")
        store.commit(record("op-A"))
        // a power-loss crash after fsync, before the atomic rename → the OLD record survives (never a torn/split record).
        assertFailsWith<RuntimeException> {
            store.commit(record("op-B")) { throw RuntimeException("crash before rename") }
        }
        assertEquals("op-A", store.read()!!.device.deviceId, "the finalize is all-or-nothing — a crash before rename keeps the OLD record")
    }

    @Test
    fun tamperedRecord_throwsFailClosed_neverEmptyReEnroll() {
        val file = tmp(); val c = cipher()
        FinalizedEnrollmentStore(c, file, "hub-1").commit(record())
        // byte-flip the at-rest ciphertext (an attacker corrupting the anchor)
        val bytes = file.readBytes(); bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte(); file.writeBytes(bytes)
        assertFailsWith<SecretCipherException> { FinalizedEnrollmentStore(c, file, "hub-1").read() }
        // ★ security: a tampered anchor must NOT read as null (that would be a tamper→re-enroll seizure vector) — it throws.
    }

    @Test
    fun wrongProjectAad_throwsFailClosed_relocationProof() {
        val file = tmp(); val c = cipher()
        FinalizedEnrollmentStore(c, file, "hub-1").commit(record())
        assertFailsWith<SecretCipherException> { FinalizedEnrollmentStore(c, file, "hub-OTHER").read() }
        // the record cannot be relocated to another hub's projectId (AAD binds it) — fail-closed.
    }
}
