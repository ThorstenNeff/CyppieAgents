package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.ed25519RawToSpki
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-525 Inc 2 — the device-key **persistence** + **SPKI→raw** teeth:
 *  - `loadOrGenerate()` persists ONE key and reuses it across launches (a fresh instance = a relaunch) — the fix for
 *    the fresh-per-launch key that never matched the hub anchor (`bad_signature`, never CONNECTED);
 *  - the private key is written owner-only (0600) on a POSIX FS; a corrupt custody file regenerates (never bricks);
 *  - `OperatorPopBuilder.devicePublicKeyRaw()` yields the **raw-32B** wire form via the single-source `:core` helper
 *    (the store exposes X.509 SPKI-44B), round-tripping exactly.
 */
class Cyp525PersistentDeviceKeyTest {

    @Test
    fun loadOrGenerate_persistsAcrossLaunches_sameKey() {
        val file = Files.createTempDirectory("cyp525").resolve("operator-device.key")
        val first = PersistentOperatorDeviceKey(file).loadOrGenerate() // generates + persists
        assertTrue(Files.exists(file), "first run persists the key")
        val second = PersistentOperatorDeviceKey(file).loadOrGenerate() // fresh instance = a relaunch
        assertContentEquals(first.public.encoded, second.public.encoded, "same public key across relaunch")
        assertContentEquals(first.private.encoded, second.private.encoded, "same private key across relaunch")
        assertTrue(first.public.algorithm in setOf("Ed25519", "EdDSA"), "an Ed25519 key")
    }

    @Test
    fun firstRun_writesOwnerOnlyPerms() {
        val file = Files.createTempDirectory("cyp525").resolve("operator-device.key")
        PersistentOperatorDeviceKey(file).loadOrGenerate()
        val perms = runCatching { Files.getPosixFilePermissions(file) }.getOrNull()
        if (perms != null) { // POSIX filesystem
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "the device key is 0600 (owner-only) at rest",
            )
        }
    }

    @Test
    fun corruptFile_regenerates_insteadOfBricking() {
        val file = Files.createTempDirectory("cyp525").resolve("operator-device.key")
        Files.write(file, byteArrayOf(1, 2, 3)) // garbage, not a valid key blob
        val kp = PersistentOperatorDeviceKey(file).loadOrGenerate() // must not throw
        assertTrue(kp.public.algorithm in setOf("Ed25519", "EdDSA"), "a corrupt custody file regenerates, never bricks connect")
    }

    @Test
    fun devicePublicKeyRaw_isRaw32B_andRoundTrips() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val store = KeystoreOperatorDeviceKeyStore(userVerification = { UvOutcome.Verified }, keyPair = kp)
        val raw = OperatorPopBuilder(store, NonceGenerator { ByteArray(16) }).devicePublicKeyRaw()!!
        assertEquals(32, raw.size, "the wire form is raw-32B (not the 44B SPKI the store holds)")
        assertContentEquals(store.devicePublicKey(), ed25519RawToSpki(raw), "SPKI→raw→SPKI round-trips exactly")
    }

    @Test
    fun devicePublicKeyRaw_nullWhenNotEnrolled() {
        val store = KeystoreOperatorDeviceKeyStore(userVerification = { UvOutcome.Verified }, keyPair = null)
        assertEquals(null, OperatorPopBuilder(store, NonceGenerator { ByteArray(16) }).devicePublicKeyRaw())
    }
}
