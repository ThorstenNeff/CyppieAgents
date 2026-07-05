package com.tneff.cyppieagents.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 2a — the encryption-spine teeth. Hermetic (a freshly-generated BOX keyset, no KMS/network):
 * round-trip, **AAD binding** (a moved ciphertext fails), tamper-evidence, wrong-master-key, key-version
 * routing/rotation, and fail-closed config resolution. The AEAD security itself is Tink's; these prove OUR
 * seam wires it correctly (AAD context, version stamping/routing, uniform fail-closed).
 */
class SecretCipherTest {

    private val aad = SecretAad(storeKey = "project_config", projectId = "projb", field = "apiKey")
    private fun boxCipher(version: Int = 1) =
        SecretCipherFactory.single(version, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))

    @Test fun roundTrip_recoversThePlaintext() {
        val c = boxCipher()
        val secret = c.encrypt("sk-super-secret-0001", aad)
        assertEquals("sk-super-secret-0001", c.decrypt(secret, aad))
        assertEquals(1, secret.keyVersion, "stamps the primary version")
        assertNotEquals(
            "sk-super-secret-0001",
            secret.ciphertext.decodeToString(),
            "ciphertext is not the plaintext (actually encrypted)",
        )
    }

    @Test fun aadBinding_anyContextChange_failsClosed() {
        val c = boxCipher()
        val secret = c.encrypt("s", aad)
        // A ciphertext bound to (project_config, projb, apiKey) must NOT decrypt under any other context —
        // this is what stops a ciphertext being relocated to another row / store / field.
        for (wrong in listOf(
            aad.copy(projectId = "proja"),
            aad.copy(field = "repoUrl"),
            aad.copy(storeKey = "remote_token"),
        )) {
            assertFailsWith<SecretCipherException>("relocation to $wrong must fail") { c.decrypt(secret, wrong) }
        }
        assertEquals("s", c.decrypt(secret, aad), "the correct context still works")
    }

    @Test fun tamperedCiphertext_failsClosed() {
        val c = boxCipher()
        val secret = c.encrypt("s", aad)
        val flipped = secret.ciphertext.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<SecretCipherException> { c.decrypt(EncryptedSecret(flipped, secret.keyVersion), aad) }
    }

    @Test fun wrongMasterKey_failsClosed() {
        val e = boxCipher().encrypt("s", aad) // keyset A
        val other = boxCipher()               // an independent keyset B
        assertFailsWith<SecretCipherException> { other.decrypt(e, aad) }
    }

    @Test fun keyVersion_stampedAndRouted_supportsRotation() {
        val boxV1 = MasterKeySource.Box(SecretCipherFactory.newBoxKeyset())
        val boxV2 = MasterKeySource.Box(SecretCipherFactory.newBoxKeyset())

        val v1 = SecretCipherFactory.single(1, boxV1)
        val oldSecret = v1.encrypt("old-value", aad)
        assertEquals(1, oldSecret.keyVersion)

        // Rotate: primary is now v2, but v1 stays available for decrypt.
        val rot = SecretCipherFactory.rotating(primaryVersion = 2, sources = mapOf(1 to boxV1, 2 to boxV2))
        assertEquals(2, rot.keyVersion, "new writes use the new primary")
        assertEquals("old-value", rot.decrypt(oldSecret, aad), "v1 ciphertext still decrypts after rotation")

        val newSecret = rot.encrypt("new-value", aad)
        assertEquals(2, newSecret.keyVersion)
        assertEquals("new-value", rot.decrypt(newSecret, aad))

        assertFailsWith<SecretCipherException>("an unknown version fails closed") {
            rot.decrypt(EncryptedSecret(newSecret.ciphertext, 99), aad)
        }
    }

    @Test fun aadEncoding_isInjective_noDelimiterCollision() {
        // PO-Assistant Phase-2a blocker: a naive "a|b|c" join is NOT injective — a component containing the
        // delimiter collides two DISTINCT contexts onto identical AAD bytes (("s","p|x","f") == ("s|p","x","f")
        // → "s|p|x|f"), which would let a ciphertext bound to ONE decrypt under the OTHER (relocation).
        val a = SecretAad("s", "p|x", "f")
        val b = SecretAad("s|p", "x", "f")
        assertFalse(a.bytes().contentEquals(b.bytes()), "distinct contexts must NOT share AAD bytes (injective)")

        // The load-bearing consequence: a ciphertext bound to `a` does NOT decrypt under the colliding `b`.
        val c = boxCipher()
        val secret = c.encrypt("value", a)
        assertFailsWith<SecretCipherException>("delimiter collision must not permit cross-context decrypt") {
            c.decrypt(secret, b)
        }
        assertEquals("value", c.decrypt(secret, a), "the true context still works")

        // A field also can't absorb another component's tail: ("s","p","x|f") vs ("s","p|x","f") stay distinct.
        assertFalse(
            SecretAad("s", "p", "x|f").bytes().contentEquals(SecretAad("s", "p|x", "f").bytes()),
            "length-prefix disambiguates where the delimiter lands",
        )
    }

    @Test fun fromConfig_box_failsClosedWithoutMasterKey_andWorksWithOne() {
        assertFailsWith<SecretCipherException> {
            SecretCipherFactory.fromConfig(EncryptionConfig(MasterKeyMode.BOX), masterKey = null)
        }
        assertFailsWith<SecretCipherException> {
            SecretCipherFactory.fromConfig(EncryptionConfig(MasterKeyMode.BOX), masterKey = "  ")
        }
        val c = SecretCipherFactory.fromConfig(EncryptionConfig(MasterKeyMode.BOX), masterKey = SecretCipherFactory.newBoxKeyset())
        assertEquals("x", c.decrypt(c.encrypt("x", aad), aad))
    }

    @Test fun fromConfig_kms_failsClosedWithoutKekUri() {
        assertFailsWith<SecretCipherException> {
            SecretCipherFactory.fromConfig(EncryptionConfig(MasterKeyMode.KMS, kekUri = null), masterKey = null)
        }
    }

    @Test fun newBoxKeyset_isFreshEachTime() {
        assertNotEquals(SecretCipherFactory.newBoxKeyset(), SecretCipherFactory.newBoxKeyset(), "each provisioned key is unique")
        assertTrue(SecretCipherFactory.newBoxKeyset().isNotBlank())
    }
}
