package com.tneff.cyppieagents.crypto

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-441 (S-C) crypto teeth — the raw-format correctness the Reviewer gates against (§7, Noise-ready static) and
 * the idempotent/rotation-safe [HubIdentityProvisioner]. Hermetic (JDK-native EdEC/XDH, temp SecretStore + file).
 *
 * ★ X25519 raw-format tooth: [x25519_rawStatic_isNoiseConsumable_dhAgrees] — the raw LE public feeds BACK into a
 * working DH (both directions agree), which is exactly what `noise-java` does with the static; a wrong endianness
 * or a non-raw encoding would make the agreement fail → red.
 * ★ Ed25519 PoP tooth: [ed25519_sign_verify_roundtrip_overRaw] — a signature made with the raw seed verifies under
 * the raw public; tamper → false. Proves the RFC-8032 raw public reconstructs correctly (the PoP anchor).
 */
class HubIdentityCryptoTest {

    private fun tempDir(): Path = Files.createTempDirectory("cyp441")
    private fun store(dir: Path) =
        SqliteSecretStore(dir.resolve("secrets.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() })

    // ---- RawKeys: Ed25519 ----

    @Test fun ed25519_sign_verify_roundtrip_overRaw() {
        val kp = RawKeys.generateEd25519()
        assertEquals(32, kp.privateRaw.size); assertEquals(32, kp.publicRaw.size)
        val msg = "cp-nonce-abc123".encodeToByteArray()
        val sig = RawKeys.ed25519Sign(kp.privateRaw, msg)
        assertTrue(RawKeys.ed25519Verify(kp.publicRaw, msg, sig), "raw public verifies a raw-seed signature")
        assertFalse(RawKeys.ed25519Verify(kp.publicRaw, "tampered".encodeToByteArray(), sig), "tampered msg → false")
        val other = RawKeys.generateEd25519()
        assertFalse(RawKeys.ed25519Verify(other.publicRaw, msg, sig), "a different public does not verify")
    }

    @Test fun ed25519_publicRaw_encode_decode_roundtrip() {
        val kp = RawKeys.generateEd25519()
        val point = RawKeys.decodeEd25519Public(kp.publicRaw)
        assertContentEquals(kp.publicRaw, RawKeys.encodeEd25519Public(point), "point re-encodes to the same raw 32B")
    }

    // ---- RawKeys: X25519 (Noise static) ----

    @Test fun x25519_rawStatic_isNoiseConsumable_dhAgrees() {
        val a = RawKeys.generateX25519()
        val b = RawKeys.generateX25519()
        assertEquals(32, a.publicRaw.size, "X25519 public is a raw 32-byte static (Noise-ready)")
        assertEquals(32, a.privateRaw.size)
        // The raw LE public feeds back into a working DH from BOTH sides → identical shared secret.
        val ab = RawKeys.x25519Dh(a.privateRaw, b.publicRaw)
        val ba = RawKeys.x25519Dh(b.privateRaw, a.publicRaw)
        assertEquals(32, ab.size)
        assertContentEquals(ab, ba, "DH agrees → the raw static round-trips correctly (noise-java consumes this)")
        assertFalse(ab.all { it == 0.toByte() }, "shared secret is not all-zero")
    }

    @Test fun littleEndian_roundtrip_fixed32() {
        val kp = RawKeys.generateX25519()
        val u = RawKeys.fromLittleEndian(kp.publicRaw)
        assertContentEquals(kp.publicRaw, RawKeys.toLittleEndian(u, 32), "LE encode/decode round-trips at fixed 32B")
    }

    // ---- HubIdentity provisioning ----

    @Test fun ensure_mints_then_idempotentLoad_sameAnchor() {
        val dir = tempDir()
        val file = dir.resolve(".cyppie/hub-identity.json")
        val minted = store(dir).use { HubIdentityProvisioner(it, file).ensure() }
        assertTrue(minted.hubId.startsWith("hub_"))
        assertTrue(Files.exists(file))
        // Re-open: a fresh provisioner over the SAME store+file returns the SAME anchor (never re-minted).
        val loaded = store(dir).use { HubIdentityProvisioner(it, file).ensure() }
        assertEquals(minted.hubId, loaded.hubId)
        assertEquals(minted.signingPubKey, loaded.signingPubKey)
        assertEquals(minted.dhPubKey, loaded.dhPubKey)
        assertEquals(minted.createdAt, loaded.createdAt)
    }

    @Test fun ensure_partialState_failsClosed_neverSilentlyRotates() {
        val dir = tempDir()
        val file = dir.resolve(".cyppie/hub-identity.json")
        store(dir).use { HubIdentityProvisioner(it, file).ensure() } // mint
        Files.delete(file) // corrupt: privates remain in the store, but the pub file is gone
        assertFailsWith<SecretCipherException> {
            store(dir).use { HubIdentityProvisioner(it, file).ensure() } // must refuse to silently re-mint/rotate
        }
    }

    @Test fun privates_liveInSecretStore_notInPubFile() {
        val dir = tempDir()
        val file = dir.resolve(".cyppie/hub-identity.json")
        store(dir).use { s ->
            val id = HubIdentityProvisioner(s, file).ensure()
            // the SecretStore holds the private halves...
            assertTrue(s.contains(HubIdentityProvisioner.SIGNING_KEY))
            assertTrue(s.contains(HubIdentityProvisioner.DH_KEY))
            // ...and the pub file carries only public material (no private base64 leaks into it).
            val fileText = Files.readString(file)
            assertFalse(fileText.contains(s.get(HubIdentityProvisioner.SIGNING_KEY)!!), "signing private not in pub file")
            assertFalse(fileText.contains(s.get(HubIdentityProvisioner.DH_KEY)!!), "dh private not in pub file")
            assertTrue(fileText.contains(id.signingPubKey))
        }
    }

    @Test fun sign_producesPoP_verifiableByPublic() {
        val dir = tempDir()
        val file = dir.resolve(".cyppie/hub-identity.json")
        store(dir).use { s ->
            val prov = HubIdentityProvisioner(s, file)
            val id = prov.ensure()
            val nonce = "cp-issued-nonce-xyz".encodeToByteArray()
            val sig = prov.sign(nonce)
            assertTrue(
                RawKeys.ed25519Verify(java.util.Base64.getDecoder().decode(id.signingPubKey), nonce, sig),
                "the PoP signature verifies under the hub's signing public",
            )
        }
    }

    @Test fun hubId_isStable_derivationOfSigningPub() {
        val a = RawKeys.generateEd25519()
        assertEquals(HubIdentityProvisioner.deriveHubId(a.publicRaw), HubIdentityProvisioner.deriveHubId(a.publicRaw))
        assertNotEquals(
            HubIdentityProvisioner.deriveHubId(a.publicRaw),
            HubIdentityProvisioner.deriveHubId(RawKeys.generateEd25519().publicRaw),
        )
    }

    @Test fun missingSigningKey_sign_failsClosed() {
        val dir = tempDir()
        store(dir).use { s ->
            // no ensure() → no signing key in custody → sign must fail closed, not sign with a null/empty key.
            assertNull(s.get(HubIdentityProvisioner.SIGNING_KEY))
            assertFailsWith<SecretCipherException> {
                HubIdentityProvisioner(s, dir.resolve(".cyppie/hub-identity.json")).sign("x".encodeToByteArray())
            }
        }
    }
}
