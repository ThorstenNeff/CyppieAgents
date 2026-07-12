package com.tneff.cyppieagents.auth.operator

import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.crypto.SecretCipherException
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SqliteSecretStore
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertIs

/**
 * CYP-472 — the durable S-B-backed operator device store. Hermetic (S-B SqliteSecretStore + a STABLE master keyset
 * per test instance, so a reopen decrypts — the [[cyp441-hubidentity]] lesson).
 *
 * ★ [enroll_survivesRestart] is the headline: an enrolled anchor persisted by one store instance is loaded by a
 *   FRESH instance over the same SecretStore (restart). ★ [wrongMasterKey_failsClosed] — a swapped master key can't
 *   read the anchor (S-B canary), and a corrupt anchor is never silently treated as "not enrolled".
 */
class SecretStoreBackedOperatorDeviceStoreTest {

    // ONE stable keyset per test instance so a reopened SecretStore decrypts (fresh-per-construction would canary-fail).
    private val masterKeyset = SecretCipherFactory.newBoxKeyset()
    private fun tempDir(): Path = Files.createTempDirectory("cyp472")
    private fun secretStore(dir: Path, keyset: String = masterKeyset) =
        SqliteSecretStore(dir.resolve("secrets.db"), MasterKeyCustody { keyset })

    private fun edDevice(id: String = "dev-1") =
        EnrolledOperatorDevice(id, DeviceKeyAlg.ED25519, RawKeys.generateEd25519().publicRaw)

    private fun fido2Es256Device() = EnrolledOperatorDevice(
        "dev-fido", DeviceKeyAlg.ES256,
        ByteArray(65).also { it[0] = 0x04.toByte() },
        credentialId = byteArrayOf(0x0A, 0x0B, 0x0C),
    )

    private fun assertSameDevice(expected: EnrolledOperatorDevice, actual: EnrolledOperatorDevice?) {
        assertIs<EnrolledOperatorDevice>(actual)
        assertEquals(expected.deviceId, actual.deviceId)
        assertEquals(expected.alg, actual.alg)
        assertContentEquals(expected.publicKey, actual.publicKey)
        assertContentEquals(expected.credentialId, actual.credentialId)
    }

    @Test fun noEnrollment_returnsNull() {
        secretStore(tempDir()).use { s ->
            assertNull(SecretStoreBackedOperatorDeviceStore(s).enrolled())
        }
    }

    /** ★ HEADLINE — persisted enroll survives a "restart": a fresh store instance over the same SecretStore loads it. */
    @Test fun enroll_survivesRestart() {
        val dir = tempDir()
        val device = edDevice()
        secretStore(dir).use { s -> SecretStoreBackedOperatorDeviceStore(s).save(device) }
        // fresh SecretStore + fresh device-store over the SAME db (== a restart)
        secretStore(dir).use { s -> assertSameDevice(device, SecretStoreBackedOperatorDeviceStore(s).enrolled()) }
    }

    @Test fun allFields_roundTrip_ed25519_and_fido2Es256() {
        val dir = tempDir()
        secretStore(dir).use { s ->
            val store = SecretStoreBackedOperatorDeviceStore(s)
            val ed = edDevice("dev-ed")
            store.save(ed)
            assertSameDevice(ed, store.enrolled())
            // overwrite with a Fido2/ES256 device (with credentialId) → all fields round-trip
            val fido = fido2Es256Device()
            store.save(fido)
            assertSameDevice(fido, store.enrolled())
        }
    }

    /** ★ A swapped master key cannot read the anchor — the S-B canary fails closed at open (tamper/seizure guard). */
    @Test fun wrongMasterKey_failsClosed() {
        val dir = tempDir()
        secretStore(dir).use { s -> SecretStoreBackedOperatorDeviceStore(s).save(edDevice()) }
        val otherKeyset = SecretCipherFactory.newBoxKeyset()
        assertFailsWith<SecretCipherException> { secretStore(dir, keyset = otherKeyset) }
    }

    /** A corrupt/undecodable anchor is NOT "not enrolled" — fail closed (never silently allow a re-enroll over it). */
    @Test fun malformedAnchor_failsClosed_notNull() {
        secretStore(tempDir()).use { s ->
            s.put("operator.device", "this-is-not-valid-json{{{")
            assertFailsWith<SecretCipherException> { SecretStoreBackedOperatorDeviceStore(s).enrolled() }
        }
    }

    /** The durable store integrates with First-Device-Enroll: the single-device invariant survives a restart. */
    @Test fun firstEnroll_durable_secondEnrollRejectedAfterRestart() {
        val dir = tempDir()
        secretStore(dir).use { s ->
            val first = OperatorDeviceEnrollment(SecretStoreBackedOperatorDeviceStore(s)).enrollFirstDevice(edDevice("dev-1"))
            assertIs<EnrollResult.Enrolled>(first)
        }
        // restart: a fresh enrollment over the same durable store sees the anchor → a 2nd enroll is the Q6 seam.
        secretStore(dir).use { s ->
            val second = OperatorDeviceEnrollment(SecretStoreBackedOperatorDeviceStore(s)).enrollFirstDevice(edDevice("dev-2"))
            assertIs<EnrollResult.Rejected>(second)
            assertEquals("already_enrolled_recovery_is_q6_seam", second.reason)
        }
    }
}
