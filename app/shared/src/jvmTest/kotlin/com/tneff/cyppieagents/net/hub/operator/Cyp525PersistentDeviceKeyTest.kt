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
    fun firstRun_writesOwnerOnly_neverGroupOrWorldReadable() {
        val file = Files.createTempDirectory("cyp525").resolve("operator-device.key")
        PersistentOperatorDeviceKey(file).loadOrGenerate()
        assertOwnerOnly(file, "the device key is owner-only (0600) at rest")
    }

    @Test
    fun regenerateOverPreExisting0644_endsOwnerOnly_notWorldReadable() {
        // F1 window: a naive create-then-chmod that writes into a PRE-EXISTING 0644 file (Files.write keeps the
        // existing perms) would leave the NEW key world-readable if the chmod is ever skipped. The atomic temp+move
        // yields 0600 regardless — this exercises the overwrite path (corrupt→regenerate) end-to-end.
        val file = Files.createTempDirectory("cyp525").resolve("operator-device.key")
        Files.write(file, byteArrayOf(9, 9, 9)) // pre-existing garbage...
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ, // ...at 0644 world-readable
                ),
            )
        }
        PersistentOperatorDeviceKey(file).loadOrGenerate() // regenerates over it
        assertOwnerOnly(file, "regenerating over a 0644 file must NOT leave the key group/world-readable")
    }

    private fun assertOwnerOnly(file: java.nio.file.Path, message: String) {
        val perms = runCatching { Files.getPosixFilePermissions(file) }.getOrNull() ?: return // non-POSIX FS: skip
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms, message)
        // Explicit anti-window assertions: the private key is NEVER group- or world-readable.
        assertTrue(PosixFilePermission.GROUP_READ !in perms, "$message — no group read")
        assertTrue(PosixFilePermission.OTHERS_READ !in perms, "$message — no world read")
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
